package com.kirana.request;

/** How a request ended for one store. Null while the request is still OPEN. */
public enum StoreOutcome {
    /** This store's quote was accepted (ACCEPTED, READY or PICKED_UP). */
    WON,
    /** The customer accepted another store's quote. */
    LOST,
    CANCELLED
}
