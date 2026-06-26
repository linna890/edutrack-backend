package com.edutrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ScanRequest(
        @NotBlank(message = "QR code is required") String qrCode,

        // Defaults to ARRIVAL if null/missing; validated to only accept known values
        @Pattern(regexp = "^(ARRIVAL|DEPARTURE)$", message = "scanType must be ARRIVAL or DEPARTURE")
        String scanType
) {}
