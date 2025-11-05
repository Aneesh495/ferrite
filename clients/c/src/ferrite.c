#include "ferrite.h"

#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

struct ferrite_client {
    char host[128];
    int port;
};

static int64_t current_time_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return ((int64_t)tv.tv_sec * 1000LL) + ((int64_t)tv.tv_usec / 1000LL);
}

ferrite_client_t* ferrite_connect(const char* host, int port) {
    if (!host || port <= 0) return NULL;

    ferrite_client_t* client = (ferrite_client_t*)calloc(1, sizeof(ferrite_client_t));
    if (!client) return NULL;

    strncpy(client->host, host, sizeof(client->host) - 1);
    client->port = port;
    return client;
}

static int send_http_request(
    const char* host,
    int port,
    const char* method,
    const char* path,
    const char* content_type,
    const char* body,
    char* response_buf,
    size_t response_max
) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return -1;

    struct sockaddr_in serv_addr;
    memset(&serv_addr, 0, sizeof(serv_addr));
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_port = htons((uint16_t)port);

    if (inet_pton(AF_INET, host, &serv_addr.sin_addr) <= 0) {
        struct hostent* he = gethostbyname(host);
        if (!he) {
            close(sock);
            return -1;
        }
        memcpy(&serv_addr.sin_addr, he->h_addr_list[0], (size_t)he->h_length);
    }

    if (connect(sock, (struct sockaddr*)&serv_addr, sizeof(serv_addr)) < 0) {
        close(sock);
        return -1;
    }

    size_t body_len = body ? strlen(body) : 0;
    char req[4096];
    int req_len = snprintf(
        req, sizeof(req),
        "%s %s HTTP/1.1\r\n"
        "Host: %s:%d\r\n"
        "Content-Type: %s\r\n"
        "Content-Length: %zu\r\n"
        "Connection: close\r\n\r\n",
        method, path, host, port,
        content_type ? content_type : "text/plain",
        body_len
    );

    if (req_len < 0 || (size_t)req_len >= sizeof(req)) {
        close(sock);
        return -1;
    }

    if (send(sock, req, (size_t)req_len, 0) < 0) {
        close(sock);
        return -1;
    }

    if (body_len > 0) {
        if (send(sock, body, body_len, 0) < 0) {
            close(sock);
            return -1;
        }
    }

    if (response_buf && response_max > 0) {
        ssize_t received = recv(sock, response_buf, response_max - 1, 0);
        if (received >= 0) {
            response_buf[received] = '\0';
        }
    }

    close(sock);
    return 0;
}

int ferrite_write(ferrite_client_t* client, const char* line_protocol) {
    if (!client || !line_protocol) return -1;
    return send_http_request(
        client->host,
        client->port,
        "POST",
        "/write",
        "text/plain",
        line_protocol,
        NULL,
        0
    );
}

int ferrite_write_point(
    ferrite_client_t* client,
    const char* metric,
    const char* tags,
    double value,
    int64_t timestamp_ms
) {
    if (!client || !metric) return -1;
    if (timestamp_ms <= 0) {
        timestamp_ms = current_time_ms();
    }

    char line[1024];
    int len;
    int64_t timestamp_ns = timestamp_ms * 1000000LL;

    if (tags && strlen(tags) > 0) {
        len = snprintf(
            line, sizeof(line),
            "%s,%s value=%.6f %lld\n",
            metric, tags, value, (long long)timestamp_ns
        );
    } else {
        len = snprintf(
            line, sizeof(line),
            "%s value=%.6f %lld\n",
            metric, value, (long long)timestamp_ns
        );
    }

    if (len < 0 || (size_t)len >= sizeof(line)) return -1;
    return ferrite_write(client, line);
}

int ferrite_health(ferrite_client_t* client, char* out_buf, size_t max_len) {
    if (!client) return -1;
    return send_http_request(
        client->host,
        client->port,
        "GET",
        "/health",
        "application/json",
        NULL,
        out_buf,
        max_len
    );
}

void ferrite_disconnect(ferrite_client_t* client) {
    if (client) {
        free(client);
    }
}
