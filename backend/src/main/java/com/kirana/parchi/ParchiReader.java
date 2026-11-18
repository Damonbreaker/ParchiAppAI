package com.kirana.parchi;

import com.kirana.parchi.dto.ScanResultDto;

/**
 * Reads a parchi photo into scanned items (name/qty/unit + optional catalogue polls).
 * Exactly one implementation is active: local-ocr (legacy dual-OCR + catalogue) or mock (tests).
 */
public interface ParchiReader {

    default ScanResultDto read(byte[] image, String contentType) {
        return read(image, contentType, "customer");
    }

    ScanResultDto read(byte[] image, String contentType, String role);
}
