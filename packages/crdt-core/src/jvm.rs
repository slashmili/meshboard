//! Experimental desktop JNI bridge. Opaque IDs, never raw pointers, cross JNI.
use crate::Board;
use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Mutex, OnceLock};

const MAX_BYTES: usize = 4 * 1024 * 1024;
#[derive(Default)]
struct Boards {
    next_id: i64,
    entries: HashMap<i64, Board>,
}
static BOARDS: OnceLock<Mutex<Boards>> = OnceLock::new();

fn boundary<T: Default>(
    env: &mut JNIEnv,
    action: impl FnOnce(&mut JNIEnv) -> Result<T, String>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| action(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            T::default()
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "Rust CRDT operation panicked",
            );
            T::default()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_meshboard_crdt_CrdtJni_create(
    mut env: JNIEnv,
    _this: JObject,
    client_id: jlong,
) -> jlong {
    boundary(&mut env, |_| {
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
    })
}

#[no_mangle]
pub extern "system" fn Java_meshboard_crdt_CrdtJni_call(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    operation: jint,
    input: JByteArray,
) -> jbyteArray {
    boundary(&mut env, |env| {
        if env.get_array_length(&input).map_err(|e| e.to_string())? as usize > MAX_BYTES {
            return Err("CRDT input exceeds 4 MiB".into());
        }
        let input = env.convert_byte_array(&input).map_err(|e| e.to_string())?;
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
                    board.validated_apply(&input).map_err(|e| e.to_string())?;
                    Vec::new()
                }
                2 => {
                    let element: serde_json::Value =
                        serde_json::from_slice(&input).map_err(|e| e.to_string())?;
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
                        .validated_remove(std::str::from_utf8(&input).map_err(|e| e.to_string())?)
                        .map_err(|e| e.to_string())?;
                    Vec::new()
                }
                4 => board.state_vector(),
                5 => board.update(&input).map_err(|e| e.to_string())?,
                6 => serde_json::to_vec(&board.elements()).map_err(|e| e.to_string())?,
                8 => {
                    let ids: Vec<String> =
                        serde_json::from_slice(&input).map_err(|e| e.to_string())?;
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
        Ok(env
            .byte_array_from_slice(&output)
            .map_err(|e| e.to_string())?
            .into_raw())
    })
}
