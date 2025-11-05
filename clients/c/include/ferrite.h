#ifndef FERRITE_H
#define FERRITE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ferrite_client ferrite_client_t;

/**
 * Creates and connects a Ferrite client to the specified host and port.
 * Returns NULL on connection failure.
 */
ferrite_client_t* ferrite_connect(const char* host, int port);

/**
 * Ingests a raw InfluxDB line protocol formatted string.
 * Returns 0 on success, negative value on failure.
 */
int ferrite_write(ferrite_client_t* client, const char* line_protocol);

/**
 * Convenience helper to format and write a single metric point.
 * tags may be NULL or comma-separated pairs (e.g. "host=srv-01,region=us-east").
 * If timestamp_ms <= 0, uses current system time.
 */
int ferrite_write_point(
    ferrite_client_t* client,
    const char* metric,
    const char* tags,
    double value,
    int64_t timestamp_ms
);

/**
 * Queries node health status into the provided buffer.
 * Returns HTTP status code (200 on success).
 */
int ferrite_health(ferrite_client_t* client, char* out_buf, size_t max_len);

/**
 * Closes socket connection and frees client resources.
 */
void ferrite_disconnect(ferrite_client_t* client);

#ifdef __cplusplus
}
#endif

#endif /* FERRITE_H */
