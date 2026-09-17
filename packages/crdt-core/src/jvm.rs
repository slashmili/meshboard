//! Experimental desktop/Android JNI bridge. Opaque IDs, never raw pointers, cross JNI.
use crate::bridge;
use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

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
    boundary(&mut env, |_| bridge::create(client_id))
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
        if env.get_array_length(&input).map_err(|e| e.to_string())? as usize > bridge::MAX_BYTES {
            return Err("CRDT input exceeds 4 MiB".into());
        }
        let input = env.convert_byte_array(&input).map_err(|e| e.to_string())?;
        let output = bridge::call(handle, operation, &input)?;
        Ok(env
            .byte_array_from_slice(&output)
            .map_err(|e| e.to_string())?
            .into_raw())
    })
}
