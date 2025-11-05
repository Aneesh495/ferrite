#include "ferrite.h"
#include <stdio.h>

int main(void) {
    printf("[c-client] Connecting to Ferrite daemon on 127.0.0.1:8086...\n");
    ferrite_client_t* client = ferrite_connect("127.0.0.1", 8086);
    if (!client) {
        fprintf(stderr, "Failed to initialize Ferrite client.\n");
        return 1;
    }

    printf("[c-client] Writing sample metric points...\n");
    ferrite_write_point(client, "sensor_temp", "sensor_id=sn-100,room=lab_a", 24.35, 0);
    ferrite_write_point(client, "sensor_temp", "sensor_id=sn-100,room=lab_a", 24.42, 0);

    char health_resp[1024];
    if (ferrite_health(client, health_resp, sizeof(health_resp)) == 0) {
        printf("[c-client] Health response:\n%s\n", health_resp);
    }

    ferrite_disconnect(client);
    printf("[c-client] Disconnected successfully.\n");
    return 0;
}
