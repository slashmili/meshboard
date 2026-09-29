#ifndef MESHBOARD_CRDT_H
#define MESHBOARD_CRDT_H
#include <stdint.h>
#include <stddef.h>

/* Private preview ABI. No Rust/board pointers cross this boundary.
 * status: 0 success, 1 invalid input/handle, 2 caught Rust panic.
 * data is an owned byte buffer (UTF-8 error on failure), NOT NUL terminated.
 * Call result_free exactly once for EVERY returned result, including errors.
 * Do not alter fields or retain data after freeing. Inputs are borrowed only
 * for the duration of call; NULL is allowed only when input_len is zero.
 */
typedef struct {
    int64_t handle;
    int32_t status;
    uint8_t *data;
    size_t len;
} MeshboardCrdtResult;

/* client_id = -1 generates an independent ID; positive IDs are test-only. */
MeshboardCrdtResult meshboard_crdt_create(int64_t client_id);
/* Operations: 1 apply, 2 put JSON, 3 remove UTF-8 ID, 4 state vector,
 * 5 update for supplied state vector, 6 elements JSON, 7 close, 8 remove IDs JSON.
 * Input/output limits: 4 MiB; validated document/update limits: 2 MiB.
 * Calls are serialized inside Rust. Closed handles are never reused.
 */
MeshboardCrdtResult meshboard_crdt_call(int64_t handle, int32_t operation,
                                     const uint8_t *input, size_t input_len);
void meshboard_crdt_result_free(MeshboardCrdtResult result);
#endif
