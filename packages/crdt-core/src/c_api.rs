//! Owned byte results and caught panics; all documents stay in the shared registry.
use crate::bridge;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;

#[repr(C)]
pub struct MeshboardCrdtResult {
    handle: i64,
    status: i32,
    data: *mut u8,
    len: usize,
}

fn result(handle: i64, status: i32, bytes: Vec<u8>) -> MeshboardCrdtResult {
    let len = bytes.len();
    let data = if len == 0 {
        ptr::null_mut()
    } else {
        Box::into_raw(bytes.into_boxed_slice()) as *mut u8
    };
    MeshboardCrdtResult {
        handle,
        status,
        data,
        len,
    }
}

fn boundary(action: impl FnOnce() -> Result<(i64, Vec<u8>), String>) -> MeshboardCrdtResult {
    match catch_unwind(AssertUnwindSafe(action)) {
        Ok(Ok((handle, bytes))) => result(handle, 0, bytes),
        Ok(Err(error)) => result(0, 1, error.into_bytes()),
        Err(_) => result(0, 2, b"Rust CRDT operation panicked".to_vec()),
    }
}

#[no_mangle]
pub extern "C" fn meshboard_crdt_create(client_id: i64) -> MeshboardCrdtResult {
    boundary(|| bridge::create(client_id).map(|handle| (handle, Vec::new())))
}

/// # Safety
/// Non-empty input must point to input_len readable bytes for the duration of this call.
#[no_mangle]
pub unsafe extern "C" fn meshboard_crdt_call(
    handle: i64,
    operation: i32,
    input: *const u8,
    input_len: usize,
) -> MeshboardCrdtResult {
    boundary(|| {
        if input_len > bridge::MAX_BYTES {
            return Err("CRDT input exceeds 4 MiB".into());
        }
        let bytes = if input_len == 0 {
            &[]
        } else {
            if input.is_null() {
                return Err("Null CRDT input".into());
            }
            unsafe { std::slice::from_raw_parts(input, input_len) }
        };
        bridge::call(handle, operation, bytes).map(|bytes| (handle, bytes))
    })
}

/// # Safety
/// Must receive an unmodified result from this library, exactly once.
#[no_mangle]
pub unsafe extern "C" fn meshboard_crdt_result_free(result: MeshboardCrdtResult) {
    if !result.data.is_null() {
        unsafe {
            drop(Box::from_raw(ptr::slice_from_raw_parts_mut(
                result.data,
                result.len,
            )))
        };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn consume(result: MeshboardCrdtResult) -> (i64, i32, Vec<u8>) {
        let data = if result.len == 0 {
            Vec::new()
        } else {
            unsafe { std::slice::from_raw_parts(result.data, result.len).to_vec() }
        };
        let values = (result.handle, result.status, data);
        unsafe { meshboard_crdt_result_free(result) };
        values
    }

    #[test]
    fn ownership_errors_and_stale_handles() {
        let (handle, status, bytes) = consume(meshboard_crdt_create(-1));
        assert_eq!(status, 0);
        assert!(handle > 0 && bytes.is_empty());
        unsafe {
            let (_, status, bytes) = consume(meshboard_crdt_call(handle, 4, ptr::null(), 0));
            assert_eq!((status, bytes), (0, vec![0]));
            for (op, len) in [(999, 0), (1, 1), (1, bridge::MAX_BYTES + 1)] {
                assert_eq!(
                    consume(meshboard_crdt_call(handle, op, ptr::null(), len)).1,
                    1
                );
            }
            assert_eq!(consume(meshboard_crdt_call(handle, 7, ptr::null(), 0)).1, 0);
            assert_eq!(consume(meshboard_crdt_call(handle, 4, ptr::null(), 0)).1, 1);
            assert_eq!(consume(meshboard_crdt_call(handle, 7, ptr::null(), 0)).1, 1);
        }
        assert_eq!(consume(meshboard_crdt_create(0)).1, 1);
        let (next, _, _) = consume(meshboard_crdt_create(-1));
        assert!(next > handle);
        unsafe {
            consume(meshboard_crdt_call(next, 7, ptr::null(), 0));
        }
    }

    #[test]
    fn panic_never_unwinds_across_c_boundary() {
        let (_, status, bytes) = consume(boundary(|| panic!("test panic")));
        assert_eq!(status, 2);
        assert_eq!(bytes, b"Rust CRDT operation panicked");
    }
}
