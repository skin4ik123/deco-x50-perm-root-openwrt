/* InternetDeco: read-only, ECC-aware Linux MTD data stream. No OOB/restore API.
 * MTD ABI subset from Linux v4.4 include/uapi/mtd/mtd-abi.h (GPL syscall note).
 * SHA256 uses the pinned Dropbear tree's LibTomCrypt (public domain).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>
#include "tomcrypt.h"

struct mtd_info_user {
    uint8_t type;
    uint32_t flags, size, erasesize, writesize, oobsize;
    uint64_t padding;
};
struct mtd_ecc_stats { uint32_t corrected, failed, badblocks, bbtblocks; };
#define MEMGETINFO _IOR('M', 1, struct mtd_info_user)
#define MEMGETBADBLOCK _IOW('M', 11, int64_t)
#define ECCGETSTATS _IOR('M', 18, struct mtd_ecc_stats)
_Static_assert(sizeof(struct mtd_info_user) == 32, "MTD info ABI");
_Static_assert(sizeof(struct mtd_ecc_stats) == 16, "MTD ECC ABI");
_Static_assert(sizeof(off_t) == 8, "64-bit offsets required");

static void fail(const char *code) {
    /* Never print NAND bytes or an arbitrary device-supplied error string. */
    fprintf(stderr, "NAND_READER_ERROR=%s\n", code);
    exit(3);
}
static int selftest(void) {
    hash_state state;
    unsigned char out[32];
    const unsigned char expected[32] = {
        0xba,0x78,0x16,0xbf,0x8f,0x01,0xcf,0xea,0x41,0x41,0x40,0xde,0x5d,0xae,0x22,0x23,
        0xb0,0x03,0x61,0xa3,0x96,0x17,0x7a,0x9c,0xb4,0x10,0xff,0x61,0xf2,0x00,0x15,0xad
    };
    return sha256_init(&state) == CRYPT_OK &&
        sha256_process(&state, (const unsigned char *)"abc", 3) == CRYPT_OK &&
        sha256_done(&state, out) == CRYPT_OK && !memcmp(out, expected, 32);
}
static unsigned int badmap(int fd, const struct mtd_info_user *info, unsigned char *map) {
    unsigned int blocks = info->size / info->erasesize;
    for (unsigned int i = 0; i < blocks; i++) {
        int64_t offset = (int64_t)i * info->erasesize;
        int value = ioctl(fd, MEMGETBADBLOCK, &offset);
        if (value < 0) fail("badblock_query");
        map[i] = value != 0;
    }
    return blocks;
}
static void write_all(const unsigned char *data, size_t length) {
    while (length) {
        ssize_t n = write(STDOUT_FILENO, data, length);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) fail("stream_write");
        data += n; length -= (size_t)n;
    }
}
int main(int argc, char **argv) {
    if (!selftest()) fail("sha256_selftest");
    if (argc == 2 && !strcmp(argv[1], "--selftest")) { puts("SHA256_SELFTEST=OK"); return 0; }
    if (argc != 4 || (strcmp(argv[1], "dump") && strcmp(argv[1], "hash"))) fail("arguments");
    if (getuid() || geteuid()) fail("uid");
    char *end;
    long index = strtol(argv[2], &end, 10);
    if (*end || !*argv[2] || index < 0 || index > 15) fail("partition_index");
    if (strlen(argv[3]) != 32 || strspn(argv[3], "0123456789abcdef") != 32) fail("nonce");
    char path[32]; snprintf(path, sizeof(path), "/dev/mtd%ld", index);
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) fail("open_readonly");
    struct stat st;
    if (fstat(fd, &st) || !S_ISCHR(st.st_mode) || major(st.st_rdev) != 90 ||
        minor(st.st_rdev) != (unsigned long)index * 2) fail("mtd_character_device");
    struct mtd_info_user info;
    if (ioctl(fd, MEMGETINFO, &info)) fail("geometry_query");
    if ((info.type != 4 && info.type != 8) || !info.size || info.size > (64U << 20) ||
        info.erasesize != 131072 || info.writesize != 2048 || info.size % info.erasesize)
        fail("geometry");
    unsigned char map[512], after_map[512], buffer[16384], digest[32];
    struct mtd_ecc_stats before, after;
    if (ioctl(fd, ECCGETSTATS, &before)) fail("ecc_before");
    unsigned int blocks = badmap(fd, &info, map);
    printf("{\"protocol\":\"IDCNAND1\",\"nonce\":\"%s\",\"index\":%ld,\"bytes\":%u,"
        "\"erase_bytes\":%u,\"write_bytes\":%u,\"oob_bytes\":%u,\"bad_blocks\":[",
        argv[3], index, info.size, info.erasesize, info.writesize, info.oobsize);
    int comma = 0;
    for (unsigned int i = 0; i < blocks; i++) if (map[i]) {
        printf("%s%u", comma ? "," : "", i); comma = 1;
    }
    puts("]}");
    if (fflush(stdout)) fail("header_write");
    hash_state state;
    if (sha256_init(&state) != CRYPT_OK) fail("hash_init");
    for (uint32_t offset = 0; offset < info.size; offset += sizeof(buffer)) {
        if (map[offset / info.erasesize]) memset(buffer, 255, sizeof(buffer));
        else {
            size_t length = 0;
            while (length < sizeof(buffer)) {
                ssize_t n = pread(fd, buffer + length, sizeof(buffer) - length, offset + length);
                if (n < 0 && errno == EINTR) continue;
                if (n <= 0) fail("mtd_short_or_failed_read");
                length += (size_t)n;
            }
        }
        if (sha256_process(&state, buffer, sizeof(buffer)) != CRYPT_OK) fail("hash_process");
        if (!strcmp(argv[1], "dump")) write_all(buffer, sizeof(buffer));
    }
    if (ioctl(fd, ECCGETSTATS, &after)) fail("ecc_after");
    if (after.failed != before.failed) fail("uncorrectable_ecc");
    badmap(fd, &info, after_map);
    if (memcmp(map, after_map, blocks)) fail("badblock_map_changed");
    if (sha256_done(&state, digest) != CRYPT_OK) fail("hash_done");
    printf("{\"protocol\":\"IDCNAND1_END\",\"nonce\":\"%s\",\"index\":%ld,\"bytes\":%u,\"sha256\":\"",
        argv[3], index, info.size);
    for (unsigned int i = 0; i < sizeof(digest); i++) printf("%02x", digest[i]);
    printf("\",\"ecc_failed_before\":%u,\"ecc_failed_after\":%u,\"ecc_corrected_delta\":%u,\"badblock_map_stable\":true}\n",
        before.failed, after.failed, (uint32_t)(after.corrected - before.corrected));
    if (fflush(stdout)) fail("trailer_write");
    close(fd);
    return 0;
}
