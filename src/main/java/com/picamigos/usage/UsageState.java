package com.picamigos.usage;

/** An agent's availability with respect to its rolling usage window. */
public enum UsageState {
    /** Available for delegation. */
    OK,
    /** A usage/rate limit was hit; the agent should be avoided until the window refreshes. */
    RATE_LIMITED
}
