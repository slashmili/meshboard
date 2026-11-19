//! Bounded embedded raster image headers; pixel decoding stays in the renderers.
use base64::{engine::general_purpose::STANDARD, Engine};
pub(crate) fn valid(data: &str) -> bool {
    if data.len() > 699100 {
        return false;
    }
    let Some((prefix, encoded)) = data.split_once(',') else {
        return false;
    };
    if !matches!(prefix, "data:image/png;base64" | "data:image/jpeg;base64") {
        return false;
    }
    let Ok(bytes) = STANDARD.decode(encoded) else {
        return false;
    };
    if bytes.is_empty() || bytes.len() > 512 * 1024 || STANDARD.encode(&bytes) != encoded {
        return false;
    }
    let u16 = |i: usize| {
        bytes
            .get(i..i + 2)
            .map(|b| u32::from(b[0]) * 256 + u32::from(b[1]))
            .unwrap_or(0)
    };
    let u32 = |i: usize| u16(i) * 65536 + u16(i + 2);
    let (mut width, mut height) = (0, 0);
    if prefix == "data:image/png;base64"
        && bytes.len() >= 33
        && bytes.starts_with(&[137, 80, 78, 71, 13, 10, 26, 10])
        && u32(8) == 13
        && &bytes[12..16] == b"IHDR"
    {
        width = u32(16);
        height = u32(20);
        let mut ended = false;
        let mut i = 8;
        while i + 12 <= bytes.len() {
            let length = u32(i) as usize;
            let kind = &bytes[i + 4..i + 8];
            if length > bytes.len() - i - 12 || kind == b"acTL" || (i != 8 && kind == b"IHDR") {
                return false;
            }
            i += 12 + length;
            if kind == b"IEND" {
                ended = length == 0 && i == bytes.len();
                break;
            }
        }
        if !ended {
            return false;
        }
    } else if prefix == "data:image/jpeg;base64" && bytes.starts_with(&[255, 216]) {
        let mut i = 2;
        while i + 3 < bytes.len() {
            if bytes[i] != 255 {
                break;
            }
            i += 1;
            while bytes.get(i) == Some(&255) {
                i += 1;
            }
            let Some(&marker) = bytes.get(i) else {
                break;
            };
            i += 1;
            if matches!(marker, 218 | 217) {
                break;
            }
            let length = u16(i) as usize;
            if length < 2 || i + length > bytes.len() {
                break;
            }
            if matches!(marker, 192 | 194) && length >= 8 {
                height = u16(i + 3);
                width = u16(i + 5);
                break;
            }
            i += length;
        }
    }
    (1..=1024).contains(&width) && (1..=1024).contains(&height)
}
