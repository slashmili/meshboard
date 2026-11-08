//! Yjs-compatible board state and validated operations for native applications.
use serde_json::Value;
use yrs::types::ToJson;
use yrs::updates::{decoder::Decode, encoder::Encode};
use yrs::{Any, Doc, Map, MapRef, ReadTxn, StateVector, Transact, Update};

#[cfg(feature = "jvm")]
mod jvm;

#[cfg(any(feature = "jvm", feature = "c-api"))]
mod bridge;
#[cfg(feature = "c-api")]
mod c_api;

mod error;
pub use error::BoardError;

/// An in-memory board replica. Use validated operations for application input.
#[derive(Debug)]
pub struct Board {
    doc: Doc,
    elements: MapRef,
}

impl Board {
    fn staged(
        &mut self,
        change: impl FnOnce(&Board) -> Result<(), BoardError>,
    ) -> Result<(), BoardError> {
        let candidate = Board::new(self.doc.client_id().get());
        candidate.apply(&self.update(&[0])?)?;
        change(&candidate)?;
        candidate.validate()?;
        *self = candidate;
        Ok(())
    }

    /// Applies a remote update atomically after validating the resulting board.
    ///
    /// # Errors
    /// Rejects malformed, oversized, or invalid board state without committing it.
    pub fn validated_apply(&mut self, update: &[u8]) -> Result<(), BoardError> {
        if update.len() > 2 * 1024 * 1024 {
            return Err("CRDT update exceeds preview limits".into());
        }
        self.staged(|candidate| candidate.apply(update))
    }

    /// Inserts an element only if the resulting board passes validation.
    ///
    /// # Errors
    /// Rejects invalid elements or a board exceeding its size limits.
    pub fn validated_put(&mut self, id: &str, value: &Value) -> Result<(), BoardError> {
        self.staged(|candidate| candidate.put(id, value))
    }

    /// Removes an element atomically, retaining the board on validation failure.
    ///
    /// # Errors
    /// Returns an error if the resulting board fails validation.
    pub fn validated_remove(&mut self, id: &str) -> Result<(), BoardError> {
        self.staged(|candidate| {
            candidate.remove(id);
            Ok(())
        })
    }

    /// Removes a batch atomically, retaining the board on validation failure.
    ///
    /// # Errors
    /// Rejects oversized batches or a resulting board that fails validation.
    pub fn validated_remove_all(&mut self, ids: &[String]) -> Result<(), BoardError> {
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

    fn validate(&self) -> Result<(), BoardError> {
        #[derive(serde::Deserialize)]
        #[serde(deny_unknown_fields)]
        struct Point {
            x: f64,
            y: f64,
            #[serde(default)]
            pressure: Option<f64>,
        }
        #[derive(serde::Deserialize)]
        #[serde(deny_unknown_fields)]
        struct Element {
            id: String,
            r#type: String,
            color: String,
            width: f64,
            points: Vec<Point>,
            #[serde(default)]
            text: Option<String>,
        }
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
        let json = self.try_elements()?;
        if self.update(&[0])?.len() > 2 * 1024 * 1024
            || serde_json::to_vec(&json)?.len() > 2 * 1024 * 1024
        {
            return Err("CRDT state exceeds preview limits".into());
        }
        for (id, value) in json.as_object().ok_or("Invalid elements map")? {
            let e: Element = serde_json::from_value(value.clone())?;
            let is_text = matches!(e.r#type.as_str(), "text" | "sticky");
            // Presence matters: null must not bypass the same strict schema as web/Kotlin.
            let valid_text = if is_text {
                e.text
                    .as_ref()
                    .is_some_and(|text| (1..=500).contains(&text.encode_utf16().count()))
            } else {
                value.get("text").is_none()
            };
            let valid_pressure = value["points"].as_array().is_some_and(|points| {
                points.iter().all(|p| match p.get("pressure") {
                    None => true,
                    Some(pressure) => {
                        e.r#type == "pen"
                            && pressure.as_f64().is_some_and(|v| (0.0..=1.0).contains(&v))
                    }
                })
            });
            if !valid_text
                || !valid_pressure
                || e.id != *id
                || id.is_empty()
                || id.len() > 80
                || !id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
                || ![
                    "pen",
                    "rectangle",
                    "ellipse",
                    "line",
                    "arrow",
                    "text",
                    "sticky",
                ]
                .contains(&e.r#type.as_str())
                || e.color.len() != 7
                || !e.color.starts_with('#')
                || !e.color.as_bytes()[1..].iter().all(u8::is_ascii_hexdigit)
                || !(1.0..=32.0).contains(&e.width)
                || e.points.is_empty()
                || e.points.len() > 12000
                || (e.r#type != "pen" && e.points.len() != 2)
                || e.points.iter().any(|p| {
                    !(-1e7..=1e7).contains(&p.x)
                        || !(-1e7..=1e7).contains(&p.y)
                        || p.pressure.is_some_and(|v| !(0.0..=1.0).contains(&v))
                })
            {
                return Err("Invalid board element".into());
            }
        }
        Ok(())
    }

    /// Creates an empty production replica with an independently generated client ID.
    #[must_use]
    pub fn random() -> Self {
        let doc = Doc::new();
        let elements = doc.get_or_insert_map("elements");
        Self { doc, elements }
    }

    /// The harness supplies unique client IDs for deterministic test replicas.
    /// Production replicas must use independently generated IDs.
    #[must_use]
    pub fn new(client_id: u64) -> Self {
        let doc = Doc::with_client_id(client_id);
        let elements = doc.get_or_insert_map("elements");
        Self { doc, elements }
    }

    /// Applies a binary update without board-schema validation.
    ///
    /// # Errors
    /// Rejects malformed encoding or updates Yrs cannot apply. Use
    /// [`Self::validated_apply`] for atomic validation of application input.
    pub fn apply(&self, update: &[u8]) -> Result<(), BoardError> {
        self.doc
            .transact_mut()
            .apply_update(Update::decode_v1(update)?)?;
        Ok(())
    }

    /// Inserts JSON without board-schema validation, for compatibility tooling.
    ///
    /// # Errors
    /// Returns an error if the value cannot be converted to Yrs JSON.
    pub fn put(&self, id: &str, value: &Value) -> Result<(), BoardError> {
        let value = Any::from_json(&serde_json::to_string(value)?)?;
        self.elements
            .insert(&mut self.doc.transact_mut(), id, value);
        Ok(())
    }

    /// Removes an element without validating the remaining board.
    pub fn remove(&self, id: &str) {
        self.elements.remove(&mut self.doc.transact_mut(), id);
    }

    /// Encodes the replica clocks used to request missing updates.
    #[must_use]
    pub fn state_vector(&self) -> Vec<u8> {
        self.doc.transact().state_vector().encode_v1()
    }

    /// Encodes changes missing from the supplied remote state vector.
    ///
    /// # Errors
    /// Rejects malformed binary state vectors.
    pub fn update(&self, target: &[u8]) -> Result<Vec<u8>, BoardError> {
        Ok(self
            .doc
            .transact()
            .encode_state_as_update_v1(&StateVector::decode_v1(target)?))
    }

    /// Serializes the board for compatibility callers using trusted input.
    ///
    /// # Panics
    /// Panics if unchecked operations introduced data that JSON cannot represent
    /// within its parsing limits. Use [`Self::try_elements`] for external data.
    #[must_use]
    pub fn elements(&self) -> Value {
        self.try_elements()
            .expect("board elements must be representable as JSON")
    }

    /// Serializes the board without panicking on JSON parsing limits.
    ///
    /// # Errors
    /// Returns an error if the serialized state cannot be parsed as JSON.
    pub fn try_elements(&self) -> Result<Value, BoardError> {
        let mut json = String::new();
        self.elements
            .to_json(&self.doc.transact())
            .to_json(&mut json);
        Ok(serde_json::from_str(&json)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn pressure_round_trips_and_invalid_samples_are_atomic() {
        let mut a = Board::new(1);
        let mut b = Board::new(2);
        let pen = json!({"id":"pressure","type":"pen","color":"#293b36","width":8,
            "points":[{"x":0,"y":0,"pressure":0},{"x":100,"y":0,"pressure":1}]});
        a.validated_put("pressure", &pen).unwrap();
        b.validated_apply(&a.update(&[0]).unwrap()).unwrap();
        assert_eq!(a.elements(), b.elements());
        for pressure in [json!(-0.1), json!(1.1), Value::Null, json!("0.5")] {
            let mut invalid = pen.clone();
            invalid["points"][0]["pressure"] = pressure;
            assert!(b.validated_put("pressure", &invalid).is_err());
            assert_eq!(a.elements(), b.elements());
        }
        let mut invalid = pen;
        invalid["type"] = json!("arrow");
        assert!(b.validated_put("pressure", &invalid).is_err());
    }

    #[test]
    fn arrow_updates_round_trip_and_reject_invalid_geometry() {
        let mut a = Board::new(1);
        let mut b = Board::new(2);
        let arrow = json!({"id":"arrow","type":"arrow","color":"#293b36","width":3,
            "points":[{"x":0,"y":0},{"x":100,"y":0}]});
        a.validated_put("arrow", &arrow).unwrap();
        b.validated_apply(&a.update(&[0]).unwrap()).unwrap();
        assert_eq!(a.elements(), b.elements());
        let mut invalid = arrow;
        invalid["points"] = json!([{"x":0,"y":0}]);
        assert!(b.validated_put("arrow", &invalid).is_err());
        assert_eq!(a.elements(), b.elements());
    }

    #[test]
    fn text_fixtures_validate_and_invalid_updates_leave_state_intact() {
        let fixtures: Value = serde_json::from_str(include_str!(
            "../../shared-protocol/fixtures/text-elements.json"
        ))
        .expect("valid fixture JSON");
        let mut board = Board::new(1);
        for element in fixtures["valid"].as_array().expect("valid fixtures") {
            let id = element["id"].as_str().expect("fixture id");
            board
                .validated_put(id, element)
                .expect("valid text element");
        }
        let before = board.try_elements().expect("valid board");
        for element in fixtures["invalid"].as_array().expect("invalid fixtures") {
            let id = element["id"].as_str().expect("fixture id");
            assert!(
                board.validated_put(id, element).is_err(),
                "accepted invalid text: {element}"
            );
            let remote = Board::new(2);
            remote.put(id, element).expect("raw fixture insertion");
            assert!(board
                .validated_apply(&remote.update(&[0]).expect("fixture update"))
                .is_err());
            assert_eq!(board.try_elements().expect("unchanged board"), before);
        }
    }

    #[test]
    fn deeply_nested_remote_values_are_rejected_without_mutation() {
        let mut board = Board::new(1);
        let previous = board.state_vector();
        let remote = Board::new(2);
        let mut value = Any::Null;
        for _ in 0..150 {
            value = Any::Array(vec![value].into());
        }
        remote
            .elements
            .insert(&mut remote.doc.transact_mut(), "deep", value);
        assert!(board
            .validated_apply(&remote.update(&[0]).unwrap())
            .is_err());
        assert_eq!(board.state_vector(), previous);
        let valid =
            json!({"id":"safe","type":"pen","color":"#123456","width":3,"points":[{"x":1,"y":2}]});
        board.validated_put("safe", &valid).unwrap();
        assert_eq!(board.elements(), json!({"safe": valid}));
    }

    #[test]
    fn board_errors_preserve_sources_and_are_thread_safe() {
        fn assert_error<T: std::error::Error + Send + Sync + 'static>() {}
        assert_error::<BoardError>();
        let error = Board::new(1).apply(&[255]).unwrap_err();
        assert!(std::error::Error::source(&error).is_some());
        assert_ne!(error.to_string(), "");
    }

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
