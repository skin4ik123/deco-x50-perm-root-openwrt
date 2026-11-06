//! Normalize Context.filesDir and our node/backup parents before RAM guards.
//! No journal, backup contents, router calls, or process-global umask changes.
use crate::{Result, target};
use std::fs::{self, OpenOptions};
use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::{Component, Path};

fn protect(path: &Path) -> Result<()> {
    match fs::DirBuilder::new().mode(0o700).create(path) {
        Ok(()) => {},
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {},
        Err(_) => return Err("Could not create private app storage".into()),
    }
    let dir = OpenOptions::new().read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_DIRECTORY | libc::O_CLOEXEC)
        .open(path).map_err(|_| "App storage is not a real directory".to_string())?;
    let meta = dir.metadata().map_err(|_| "Could not inspect app storage".to_string())?;
    if !meta.is_dir() || meta.uid() != unsafe { libc::geteuid() } {
        return Err("App storage directory has an unexpected owner".into());
    }
    // File::set_permissions uses fchmod on the inode we just inspected.
    dir.set_permissions(fs::Permissions::from_mode(0o700))
        .map_err(|_| "Could not make app storage private".to_string())?;
    let mode = dir.metadata().map_err(|_| "Could not verify app storage".to_string())?.mode();
    if mode & 0o077 != 0 { return Err("App storage permissions did not become private".into()); }
    Ok(())
}

pub fn prepare(home: &Path, host: &str) -> Result<()> {
    if !home.is_absolute() || home.components().any(|c| matches!(c, Component::ParentDir)) {
        return Err("Invalid app storage path".into());
    }
    if !host.is_empty() && target(host)? != host { return Err("Invalid device address".into()); }
    protect(home)?;
    for name in ["nodes", "backups"] {
        let parent = home.join(name);
        protect(&parent)?;
        if !host.is_empty() { protect(&parent.join(host))?; }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;
    struct Temp(std::path::PathBuf);
    impl Temp { fn new() -> Self { let p=std::env::temp_dir().join(crate::nonce());fs::create_dir(&p).unwrap();Self(p) } }
    impl Drop for Temp { fn drop(&mut self) { let _=fs::remove_dir_all(&self.0); } }
    #[test] fn android_group_writable_directories_become_private_without_changing_saved_state() {
        let t=Temp::new();let h="10.20.30.40";
        for p in [t.0.clone(),t.0.join("nodes"),t.0.join("nodes").join(h),t.0.join("backups"),t.0.join("backups").join(h)] {
            fs::create_dir_all(&p).unwrap();fs::set_permissions(p,fs::Permissions::from_mode(0o771)).unwrap();
        }
        let journal=t.0.join("nodes").join(h).join("ram-boot.json");fs::write(&journal,b"existing operation").unwrap();
        let before=fs::metadata(&journal).unwrap();prepare(&t.0,h).unwrap();prepare(&t.0,h).unwrap();
        assert_eq!(fs::read(&journal).unwrap(),b"existing operation");assert_eq!(fs::metadata(&journal).unwrap().ino(),before.ino());
        for p in [t.0.clone(),t.0.join("nodes"),t.0.join("nodes").join(h),t.0.join("backups"),t.0.join("backups").join(h)] {
            assert_eq!(fs::metadata(p).unwrap().mode()&0o777,0o700);
        }
    }
    #[test] fn directory_symlinks_are_rejected_without_chmodding_the_destination() {
        let t=Temp::new();let outside=Temp::new();fs::set_permissions(&outside.0,fs::Permissions::from_mode(0o755)).unwrap();
        symlink(&outside.0,t.0.join("nodes")).unwrap();assert!(prepare(&t.0,"10.20.30.40").is_err());
        assert_eq!(fs::metadata(&outside.0).unwrap().mode()&0o777,0o755);
    }
    #[test] fn files_and_noncanonical_hosts_are_rejected() {
        let t=Temp::new();fs::write(t.0.join("backups"),b"preserve").unwrap();assert!(prepare(&t.0,"").is_err());
        assert_eq!(fs::read(t.0.join("backups")).unwrap(),b"preserve");
        for host in ["../other","10.20.30.040","127.0.0.1"] {assert!(prepare(&t.0,host).is_err());}
    }
}
