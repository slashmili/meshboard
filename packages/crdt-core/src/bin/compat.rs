//! Test-only, one-request CLI. JSON wraps binary bytes for the Node test harness;
//! this is not the app's transport protocol or a server endpoint.
use meshboard_crdt_core::Board;
use serde::Deserialize;
use serde_json::{json, Value};
use std::io;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Request {
    client_id: u64,
    #[serde(default)]
    updates: Vec<Vec<u8>>,
    #[serde(default)]
    operations: Vec<Operation>,
    #[serde(default = "empty_vector")]
    target_state_vector: Vec<u8>,
}

fn empty_vector() -> Vec<u8> {
    vec![0]
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "lowercase", deny_unknown_fields)]
enum Operation {
    Put { id: String, element: Value },
    Remove { id: String },
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let request: Request = serde_json::from_reader(io::stdin().lock())?;
    let board = Board::new(request.client_id);
    for update in request.updates {
        board.apply(&update)?;
    }
    for operation in request.operations {
        match operation {
            Operation::Put { id, element } => board.put(&id, &element)?,
            Operation::Remove { id } => board.remove(&id),
        }
    }
    serde_json::to_writer(
        io::stdout().lock(),
        &json!({
            "elements": board.elements(),
            "stateVector": board.state_vector(),
            "update": board.update(&request.target_state_vector)?,
        }),
    )?;
    Ok(())
}
