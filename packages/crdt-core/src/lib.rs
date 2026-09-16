//! Phase 2 compatibility prototype; not yet linked into the shipped apps.
use serde_json::Value;
use yrs::types::ToJson;
use yrs::updates::{decoder::Decode, encoder::Encode};
use yrs::{Any, Doc, Map, MapRef, ReadTxn, StateVector, Transact, Update};

#[cfg(feature = "jvm")]
mod jvm;

pub struct Board {
    doc: Doc,
    elements: MapRef,
}

impl Board {
    fn staged(
        &mut self,
        change: impl FnOnce(&Board) -> Result<(), Box<dyn std::error::Error>>,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let candidate = Board::new(self.doc.client_id().get());
        candidate.apply(&self.update(&[0])?)?;
        change(&candidate)?;
        candidate.validate()?;
        *self = candidate;
        Ok(())
    }

    pub fn validated_apply(&mut self, update: &[u8]) -> Result<(), Box<dyn std::error::Error>> {
        if update.len() > 2 * 1024 * 1024 {
            return Err("CRDT update exceeds preview limits".into());
        }
        self.staged(|candidate| candidate.apply(update))
    }

    pub fn validated_put(
        &mut self,
        id: &str,
        value: &Value,
    ) -> Result<(), Box<dyn std::error::Error>> {
        self.staged(|candidate| candidate.put(id, value))
    }

    pub fn validated_remove(&mut self, id: &str) -> Result<(), Box<dyn std::error::Error>> {
        self.staged(|candidate| {
            candidate.remove(id);
            Ok(())
        })
    }

    pub fn validated_remove_all(
        &mut self,
        ids: &[String],
    ) -> Result<(), Box<dyn std::error::Error>> {
        if ids.len() > 10000 {
            return Err("Too many removed IDs".into());
        }
        self.staged(|candidate| {
            for id in ids {
                candidate.remove(id);
            }
            Ok(())
        })
    }

    fn validate(&self) -> Result<(), Box<dyn std::error::Error>> {
        let txn = self.doc.transact();
        if txn.root_refs().any(|(name, _)| name != "elements") || self.elements.len(&txn) > 2000 {
            return Err("Invalid CRDT roots or element count".into());
        }
        if self
            .elements
            .iter(&txn)
            .any(|(_, value)| !matches!(value, yrs::Out::Any(_)))
        {
            return Err("Expected atomic board elements".into());
        }
        drop(txn);
        let json = self.elements();
        if self.update(&[0])?.len() > 2 * 1024 * 1024
            || serde_json::to_vec(&json)?.len() > 2 * 1024 * 1024
        {
            return Err("CRDT state exceeds preview limits".into());
        }
        #[derive(serde::Deserialize)]
        #[serde(deny_unknown_fields)]
        struct Point {
            x: f64,
            y: f64,
        }
        #[derive(serde::Deserialize)]
        #[serde(deny_unknown_fields)]
        struct Element {
            id: String,
            r#type: String,
            color: String,
            width: f64,
            points: Vec<Point>,
        }
        for (id, value) in json.as_object().ok_or("Invalid elements map")? {
            let e: Element = serde_json::from_value(value.clone())?;
            if e.id != *id
                || id.is_empty()
                || id.len() > 80
                || !id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
                || !["pen", "rectangle", "ellipse", "line"].contains(&e.r#type.as_str())
                || e.color.len() != 7
                || !e.color.starts_with('#')
                || !e.color.as_bytes()[1..].iter().all(u8::is_ascii_hexdigit)
                || !(1.0..=32.0).contains(&e.width)
                || e.points.is_empty()
                || e.points.len() > 12000
                || (e.r#type != "pen" && e.points.len() != 2)
                || e.points
                    .iter()
                    .any(|p| !(-1e7..=1e7).contains(&p.x) || !(-1e7..=1e7).contains(&p.y))
            {
                return Err("Invalid board element".into());
            }
        }
        Ok(())
    }

    pub fn random() -> Self {
        let doc = Doc::new();
        let elements = doc.get_or_insert_map("elements");
        Self { doc, elements }
    }

    /// The harness supplies unique client IDs for deterministic test replicas.
    /// Production replicas must use independently generated IDs.
    pub fn new(client_id: u64) -> Self {
        let doc = Doc::with_client_id(client_id);
        let elements = doc.get_or_insert_map("elements");
        Self { doc, elements }
    }

    pub fn apply(&self, update: &[u8]) -> Result<(), Box<dyn std::error::Error>> {
        self.doc
            .transact_mut()
            .apply_update(Update::decode_v1(update)?)?;
        Ok(())
    }

    pub fn put(&self, id: &str, value: &Value) -> Result<(), Box<dyn std::error::Error>> {
        let value = Any::from_json(&serde_json::to_string(value)?)?;
        self.elements
            .insert(&mut self.doc.transact_mut(), id, value);
        Ok(())
    }

    pub fn remove(&self, id: &str) {
        self.elements.remove(&mut self.doc.transact_mut(), id);
    }

    pub fn state_vector(&self) -> Vec<u8> {
        self.doc.transact().state_vector().encode_v1()
    }

    pub fn update(&self, target: &[u8]) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        Ok(self
            .doc
            .transact()
            .encode_state_as_update_v1(&StateVector::decode_v1(target)?))
    }

    pub fn elements(&self) -> Value {
        let mut json = String::new();
        self.elements
            .to_json(&self.doc.transact())
            .to_json(&mut json);
        serde_json::from_str(&json).expect("Yrs emitted valid JSON")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn staged_updates_reject_bad_shapes_and_foreign_roots_without_mutation() {
        let valid =
            json!({"id":"safe","type":"pen","color":"#123456","width":3,"points":[{"x":1,"y":2}]});
        let mut board = Board::new(1);
        board.validated_put("safe", &valid).unwrap();
        let bad = Board::new(2);
        bad.put("broken", &json!({"id":"broken"})).unwrap();
        assert!(board.validated_apply(&bad.update(&[0]).unwrap()).is_err());
        assert_eq!(board.elements(), json!({"safe":valid}));
        let foreign = Doc::new();
        foreign
            .get_or_insert_map("foreign")
            .insert(&mut foreign.transact_mut(), "bad", 1);
        assert!(board
            .validated_apply(
                &foreign
                    .transact()
                    .encode_state_as_update_v1(&StateVector::default())
            )
            .is_err());
        assert!(board
            .validated_apply(&vec![0; 2 * 1024 * 1024 + 1])
            .is_err());
        assert_eq!(board.elements(), json!({"safe":valid}));
        board.validated_remove("safe").unwrap();
        assert_eq!(board.elements(), json!({}));
    }

    #[test]
    fn snapshot_and_deletion_survive_duplicate_updates() {
        let a = Board::new(1);
        let b = Board::new(2);
        a.put(
            "stroke",
            &json!({"id": "stroke", "points": [{"x": 1.5, "y": 2}]}),
        )
        .unwrap();
        let snapshot = a.update(&[0]).unwrap();
        b.apply(&snapshot).unwrap();
        assert_eq!(a.elements(), b.elements());
        a.remove("stroke");
        b.apply(&a.update(&b.state_vector()).unwrap()).unwrap();
        b.apply(&snapshot).unwrap();
        assert_eq!(b.elements(), json!({}));
    }

    #[test]
    fn malformed_binary_is_rejected() {
        let board = Board::new(1);
        assert!(board.apply(&[255]).is_err());
        assert!(board.update(&[255]).is_err());
    }
}
