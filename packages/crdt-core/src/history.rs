//! Bounded local inverse changes; remote writes invalidate only affected objects.
use serde_json::Value;
use std::collections::HashSet;

#[derive(Clone, Debug)]
pub(crate) struct Change {
    pub id: String,
    pub before: Option<Value>,
    pub after: Option<Value>,
}
#[derive(Debug, Default)]
pub(crate) struct History {
    pub undo: Vec<Vec<Change>>,
    pub redo: Vec<Vec<Change>>,
}
impl History {
    pub fn record(&mut self, before: &Value, after: &Value) {
        let ids: HashSet<_> = before
            .as_object()
            .into_iter()
            .flat_map(|v| v.keys())
            .chain(after.as_object().into_iter().flat_map(|v| v.keys()))
            .collect();
        let changes: Vec<_> = ids
            .into_iter()
            .filter_map(|id| {
                let a = before.get(id);
                let b = after.get(id);
                (a != b).then(|| Change {
                    id: id.clone(),
                    before: a.cloned(),
                    after: b.cloned(),
                })
            })
            .collect();
        if changes.is_empty() {
            return;
        }
        self.redo.clear();
        self.undo.push(changes);
        while self.undo.len() > 100 || self.bytes() > 8 * 1024 * 1024 {
            self.undo.remove(0);
        }
    }
    fn bytes(&self) -> usize {
        self.undo
            .iter()
            .chain(&self.redo)
            .flatten()
            .map(|c| {
                c.id.len()
                    + c.before.as_ref().map_or(0, |v| v.to_string().len())
                    + c.after.as_ref().map_or(0, |v| v.to_string().len())
            })
            .sum()
    }
    pub fn invalidate(&mut self, ids: &HashSet<String>) {
        for stack in [&mut self.undo, &mut self.redo] {
            for action in stack.iter_mut() {
                action.retain(|c| !ids.contains(&c.id));
            }
            stack.retain(|action| !action.is_empty());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn retained_inverse_values_have_a_byte_budget() {
        let mut history = History::default();
        for i in 0..12 {
            history.record(
                &json!({}),
                &json!({"large": {"i": i, "value": "x".repeat(1024 * 1024)}}),
            );
        }
        assert!(!history.undo.is_empty());
        assert!(history.undo.len() < 12);
        assert!(history.bytes() <= 8 * 1024 * 1024);
        assert_eq!(
            history.undo.last().unwrap()[0].after.as_ref().unwrap()["i"],
            11
        );
    }
}
