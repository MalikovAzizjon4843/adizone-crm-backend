package com.crm.entity.enums;

/**
 * Lid bosqichining voronka qadami (docs/design/director-dashboard.md §1.1, G1).
 * {@code kind = CONVERTED} bosqichlar VISITED dan yuqori hisoblanadi (rank 3).
 */
public enum FunnelStep {
    NONE(0),
    CONTACTED(1),
    VISITED(2);

    private final int rank;

    FunnelStep(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
