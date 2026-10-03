package com.databundleHum.OnetBundleHub.dtos.response;

import com.databundleHum.OnetBundleHub.entity.UnverifiedRecipient;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class BulkNumberVerificationResponse {
    int submitted;
    int duplicatesRemoved;
    int invalidNumbers;
    List<UnverifiedRecipient> records;
}
