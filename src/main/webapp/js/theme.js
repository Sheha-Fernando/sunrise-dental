/**
 * Resolves the Sunrise Dental theme's centralized chart/status CSS
 * variables (defined in style.css as --sd-chart-1..6, --sd-status-*) into
 * actual color strings. Chart.js can't consume CSS custom properties
 * directly, so this is the single place that calls getComputedStyle -
 * every page needing a real color value for a canvas or a JS-driven dot
 * goes through here instead of hardcoding hex or duplicating the lookup.
 */
const Theme = (() => {
    const CHART_VARS = ["--sd-chart-1", "--sd-chart-2", "--sd-chart-3", "--sd-chart-4", "--sd-chart-5", "--sd-chart-6"];

    function color(varName) {
        return getComputedStyle(document.documentElement).getPropertyValue(varName).trim();
    }

    function chart(index) {
        return color(CHART_VARS[index % CHART_VARS.length]);
    }

    function rgba(varName, alpha) {
        const hex = color(varName).replace("#", "");
        const full = hex.length === 3 ? hex.split("").map(c => c + c).join("") : hex;
        const num = parseInt(full, 16);
        const r = (num >> 16) & 255, g = (num >> 8) & 255, b = num & 255;
        return `rgba(${r}, ${g}, ${b}, ${alpha})`;
    }

    return { color, chart, rgba };
})();
