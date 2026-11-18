package com.kirana.request;

/** See PLAN.md "Status flows". DRAFT is not used yet: requests are created already OPEN. */
public enum RequestStatus {
    DRAFT, OPEN, ACCEPTED, READY, PICKED_UP, CANCELLED
}
