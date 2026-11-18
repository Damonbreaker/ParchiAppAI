package com.kirana.request;

/**
 * Store inbox filter. OPEN = request status OPEN; ORDERS = this store won and the order is ACCEPTED or READY;
 * CLOSED = everything else (won and picked up, lost, cancelled).
 */
public enum InboxFilter {
    OPEN, ORDERS, CLOSED
}
