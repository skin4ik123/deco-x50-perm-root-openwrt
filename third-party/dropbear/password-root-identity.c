/* Separate credentials for the owner's root-only SSH service.
 * This file is inserted into Dropbear's fill_passwd(), not into libc.
 * No system passwd/shadow or TP-Link account is changed or consulted.
 */
void fill_passwd(const char* username) {
    const char *base = "/tp_data/user_data/internetdeco-ssh";
    char hash[192];
    struct stat st;
    int directory = -1, descriptor = -1;
    ssize_t length;
    size_t i;
    if (ses.authstate.pw_name) m_free(ses.authstate.pw_name);
    if (ses.authstate.pw_dir) m_free(ses.authstate.pw_dir);
    if (ses.authstate.pw_shell) m_free(ses.authstate.pw_shell);
    if (ses.authstate.pw_passwd) m_free(ses.authstate.pw_passwd);
    ses.authstate.pw_name = ses.authstate.pw_dir = NULL;
    ses.authstate.pw_shell = ses.authstate.pw_passwd = NULL;
    if (strcmp(username, "root") != 0 || getuid() || geteuid() || getgid() || getegid()) return;
    directory = open(base, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (directory < 0) return;
    if (fstat(directory, &st) || !S_ISDIR(st.st_mode) || st.st_uid ||
            (st.st_mode & 0777) != 0700) goto done;
    descriptor = openat(directory, "password.hash", O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (descriptor < 0) goto done;
    if (fstat(descriptor, &st) || !S_ISREG(st.st_mode) || st.st_uid ||
            (st.st_mode & 0777) != 0600 || st.st_nlink != 1 ||
            st.st_size != 121) goto done;
    length = read(descriptor, hash, sizeof(hash));
    if (length != st.st_size || hash[length - 1] != '\n') goto done;
    hash[--length] = '\0';
    if (strncmp(hash, "$6$rounds=100000$", 17) != 0 || hash[33] != '$') goto done;
    for (i = 17; i < (size_t)length; i++) {
        if (i == 33) continue;
        if (!((hash[i] >= 'a' && hash[i] <= 'z') ||
              (hash[i] >= 'A' && hash[i] <= 'Z') ||
              (hash[i] >= '0' && hash[i] <= '9') ||
              hash[i] == '.' || hash[i] == '/')) goto done;
    }
    ses.authstate.pw_uid = ses.authstate.pw_gid = 0;
    ses.authstate.pw_name = m_strdup("root");
    ses.authstate.pw_dir = m_strdup("/root");
    ses.authstate.pw_shell = m_strdup("/bin/ash");
    ses.authstate.pw_passwd = m_strdup(hash);
done:
    m_burn(hash, sizeof(hash));
    if (descriptor >= 0) close(descriptor);
    close(directory);
}
