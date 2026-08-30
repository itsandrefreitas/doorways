package com.doorways;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The point both loaders share. Constants and common start-up only — block and item
 * registration lives in each loader module, because that is where the APIs diverge.
 */
public final class Doorways {

    public static final String MOD_ID = "doorways";
    public static final Logger LOGGER = LoggerFactory.getLogger("Doorways");

    /** Called by each loader once its own registration is done. */
    public static void init() {
        LOGGER.info("Doorways loaded.");
    }

    private Doorways() {}
}
