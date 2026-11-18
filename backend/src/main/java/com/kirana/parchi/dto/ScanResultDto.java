package com.kirana.parchi.dto;

import java.util.List;

/** The parsed list for the review screen; empty if nothing could be read. The photo itself is not kept. */
public record ScanResultDto(List<ScannedItemDto> items) {
}
