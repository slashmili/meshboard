//! Shared validated operations for JNI and C callers. Handles are never pointers.
use crate::Board;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

pub const MAX_BYTES: usize = 4 * 1024 * 1024;
#[derive(Default)]
struct Boards {
    next_id: i64,
    entries: HashMap<i64, Board>,
}
static BOARDS: OnceLock<Mutex<Boards>> = OnceLock::new();

pub fn create(client_id: i64) -> Result<i64, String> {
    let board = if client_id == -1 {
        Board::random()
    } else if (1..=9_007_199_254_740_991).contains(&client_id) {
        Board::new(client_id as u64)
    } else {
        return Err("Client ID must be a positive JavaScript-safe integer".into());
    };
    let mut registry = BOARDS
        .get_or_init(Default::default)
        .lock()
        .map_err(|e| e.to_string())?;
    registry.next_id = registry
        .next_id
        .checked_add(1)
        .ok_or("CRDT handle IDs exhausted")?;
    let id = registry.next_id;
    registry.entries.insert(id, board);
    Ok(id)
}

pub fn call(handle: i64, operation: i32, input: &[u8]) -> Result<Vec<u8>, String> {
    if input.len() > MAX_BYTES {
        return Err("CRDT input exceeds 4 MiB".into());
    }
    let mut registry = BOARDS
        .get_or_init(Default::default)
        .lock()
        .map_err(|e| e.to_string())?;
    let output = if operation == 7 {
        registry
            .entries
            .remove(&handle)
            .ok_or("Unknown or closed CRDT handle")?;
        Vec::new()
    } else {
        let board = registry
            .entries
            .get_mut(&handle)
            .ok_or("Unknown or closed CRDT handle")?;
        match operation {
            1 => {
                board.validated_apply(input).map_err(|e| e.to_string())?;
                Vec::new()
            }
            2 => {
                let element: serde_json::Value =
                    serde_json::from_slice(input).map_err(|e| e.to_string())?;
                let id = element
                    .get("id")
                    .and_then(|v| v.as_str())
                    .ok_or("Missing element ID")?;
                board
                    .validated_put(id, &element)
                    .map_err(|e| e.to_string())?;
                Vec::new()
            }
            3 => {
                board
                    .validated_remove(std::str::from_utf8(input).map_err(|e| e.to_string())?)
                    .map_err(|e| e.to_string())?;
                Vec::new()
            }
            4 => board.state_vector(),
            5 => board.update(input).map_err(|e| e.to_string())?,
            6 => serde_json::to_vec(&board.elements()).map_err(|e| e.to_string())?,
            8 => {
                let ids: Vec<String> = serde_json::from_slice(input).map_err(|e| e.to_string())?;
                board
                    .validated_remove_all(&ids)
                    .map_err(|e| e.to_string())?;
                Vec::new()
            }
            _ => return Err("Unknown CRDT operation".into()),
        }
    };
    if output.len() > MAX_BYTES {
        return Err("CRDT output exceeds 4 MiB".into());
    }
    Ok(output)
}
