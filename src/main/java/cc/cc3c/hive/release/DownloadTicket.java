package cc.cc3c.hive.release;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DownloadTicket(String version, @JsonProperty("expires_at") String expiresAt,
                             @JsonProperty("program_url") String programUrl,
                             @JsonProperty("configuration_url") String configurationUrl,
                             @JsonProperty("manifest_url") String manifestUrl) {
}
