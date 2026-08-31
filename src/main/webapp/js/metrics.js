/**
 * Shared metric-grid renderer - the "colored dot + large number + label"
 * treatment introduced on the dashboard (Appointment Status Summary / Clinic
 * Overview). Reused everywhere a page shows a row of at-a-glance figures, so
 * every page's metric grid looks and behaves identically.
 */
const Metrics = (() => {
    const PALETTE = ["#2A78D6", "#1BAF7A", "#B89552", "#1D5D95", "#EB6834", "#8F7440"];

    function row(items) {
        return items.map((item, index) => `<div class="status-summary-item">
            <span class="status-summary-dot" style="background:${item.color || PALETTE[index % PALETTE.length]}"></span>
            <div>
                <div class="status-summary-count">${item.value}</div>
                <div class="status-summary-label">${item.label}</div>
            </div>
        </div>`).join("");
    }

    /**
     * Renders items into container and sets the grid to exactly one row of
     * N equal columns (N = items.length), so every column divides the
     * panel's width evenly no matter how many characters are in any one
     * value or label. Use this instead of `row()` for a normal (non-compact)
     * metrics grid; `.compact` rows (see style.css) stay flex-based on
     * purpose and should keep using `row()` directly.
     */
    function render(container, items) {
        container.style.gridTemplateColumns = `repeat(${items.length}, 1fr)`;
        container.innerHTML = row(items);
    }

    return { row, render, PALETTE };
})();
