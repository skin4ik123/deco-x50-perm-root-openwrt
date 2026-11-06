use crate::{Result, nonce};
use sha2::{Digest, Sha512};
const ALPHABET: &[u8] = b"./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
pub fn password_hash(password: &str, salt: Option<&str>) -> Result<String> {
    let p = password.as_bytes();
    if password.chars().count() < 12 || p.len() > 256 || p.contains(&0) {
        return Err("Use at least 12 characters and no more than 256 UTF-8 bytes".into());
    }
    let random = nonce();
    let entropy = sha2::Sha256::digest(random.as_bytes());
    let salt_generated: String = entropy[..16]
        .iter()
        .map(|b| ALPHABET[(b & 63) as usize] as char)
        .collect();
    let salt = salt.unwrap_or(&salt_generated);
    if salt.len() != 16 || !salt.bytes().all(|b| ALPHABET.contains(&b)) {
        return Err("Invalid password hash parameters".into());
    }
    let s = salt.as_bytes();
    let digest = |v: &[u8]| Sha512::digest(v).to_vec();
    let repeat = |v: &[u8], count: usize| v.iter().copied().cycle().take(count).collect::<Vec<_>>();
    let mut buf = Vec::new();
    buf.extend(p);
    buf.extend(s);
    buf.extend(p);
    let alt = digest(&buf);
    buf.clear();
    buf.extend(p);
    buf.extend(s);
    buf.extend(repeat(&alt, p.len()));
    let mut bits = p.len();
    while bits > 0 {
        buf.extend(if bits & 1 != 0 { &alt } else { p });
        bits >>= 1
    }
    let mut result = digest(&buf);
    let pbytes = repeat(&digest(&p.repeat(p.len())), p.len());
    let sbytes = repeat(&digest(&s.repeat(16 + result[0] as usize)), s.len());
    for i in 0..100000 {
        buf.clear();
        buf.extend(if i & 1 != 0 { &pbytes } else { &result });
        if i % 3 != 0 {
            buf.extend(&sbytes)
        }
        if i % 7 != 0 {
            buf.extend(&pbytes)
        }
        buf.extend(if i & 1 != 0 { &result } else { &pbytes });
        result = digest(&buf);
    }
    let order = [
        (0, 21, 42),
        (22, 43, 1),
        (44, 2, 23),
        (3, 24, 45),
        (25, 46, 4),
        (47, 5, 26),
        (6, 27, 48),
        (28, 49, 7),
        (50, 8, 29),
        (9, 30, 51),
        (31, 52, 10),
        (53, 11, 32),
        (12, 33, 54),
        (34, 55, 13),
        (56, 14, 35),
        (15, 36, 57),
        (37, 58, 16),
        (59, 17, 38),
        (18, 39, 60),
        (40, 61, 19),
        (62, 20, 41),
    ];
    let mut out = format!("$6$rounds=100000${salt}$");
    for (a, b, c) in order {
        let mut word = (result[a] as u32) << 16 | (result[b] as u32) << 8 | result[c] as u32;
        for _ in 0..4 {
            out.push(ALPHABET[(word & 63) as usize] as char);
            word >>= 6
        }
    }
    let mut word = result[63];
    for _ in 0..2 {
        out.push(ALPHABET[(word & 63) as usize] as char);
        word >>= 6
    }
    out.push('\n');
    Ok(out)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reference() {
        let h = password_hash("test-only-password", Some("abcdefghijklmnop")).unwrap();
        let out=std::process::Command::new("python3").args(["-c","import ctypes;c=ctypes.CDLL('libcrypt.so.1');c.crypt.argtypes=[ctypes.c_char_p,ctypes.c_char_p];c.crypt.restype=ctypes.c_char_p;print(c.crypt(b'test-only-password', b'$6$rounds=100000$abcdefghijklmnop$').decode())"]).output();
        let o = out.expect("Python + libcrypt reference");
        assert!(o.status.success());
        assert_eq!(h, String::from_utf8(o.stdout).unwrap());
        assert_eq!(h.len(), 121);
        assert!(password_hash("short", None).is_err())
    }
}
