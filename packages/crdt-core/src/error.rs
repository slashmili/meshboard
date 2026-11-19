use std::{error::Error, fmt};

/// A rejected board operation, with the underlying parser or CRDT error preserved.
#[derive(Debug)]
pub struct BoardError {
    message: &'static str,
    source: Option<Box<dyn Error + Send + Sync>>,
}

impl fmt::Display for BoardError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if let Some(source) = &self.source {
            source.fmt(f)
        } else {
            f.write_str(self.message)
        }
    }
}

impl Error for BoardError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        self.source.as_deref().map(|error| error as _)
    }
}

impl From<&'static str> for BoardError {
    fn from(message: &'static str) -> Self {
        Self {
            message,
            source: None,
        }
    }
}

impl From<serde_json::Error> for BoardError {
    fn from(error: serde_json::Error) -> Self {
        Self {
            message: "invalid board JSON",
            source: Some(Box::new(error)),
        }
    }
}

impl From<yrs::encoding::read::Error> for BoardError {
    fn from(error: yrs::encoding::read::Error) -> Self {
        Self {
            message: "invalid CRDT encoding",
            source: Some(Box::new(error)),
        }
    }
}

impl From<yrs::error::UpdateError> for BoardError {
    fn from(error: yrs::error::UpdateError) -> Self {
        Self {
            message: "invalid CRDT update",
            source: Some(Box::new(error)),
        }
    }
}
