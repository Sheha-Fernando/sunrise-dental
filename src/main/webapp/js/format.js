/**
 * Shared display formatting helpers (date/time/currency/status).
 * Pure functions, no dependencies - used by appointment.js, search.js, billing.js.
 */
const Fmt = (() => {

    const MONTHS = ["January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"];

    function date(isoDate) {
        // "2026-09-01" -> "01 September 2026"
        if (!isoDate) return "";
        const [year, month, day] = isoDate.split("-").map(Number);
        return `${String(day).padStart(2, "0")} ${MONTHS[month - 1]} ${year}`;
    }

    function time(isoTime) {
        // "09:30" or "09:30:00" -> "09:30 AM"
        if (!isoTime) return "";
        const [hourStr, minuteStr] = isoTime.split(":");
        let hour = parseInt(hourStr, 10);
        const suffix = hour >= 12 ? "PM" : "AM";
        hour = hour % 12;
        if (hour === 0) hour = 12;
        return `${String(hour).padStart(2, "0")}:${minuteStr} ${suffix}`;
    }

    function weekdayDate(isoDate) {
        // "2026-09-02" -> "Wednesday, 2 September 2026"
        if (!isoDate) return "";
        const [year, month, day] = isoDate.split("-").map(Number);
        const weekday = new Date(year, month - 1, day).toLocaleDateString("en-US", { weekday: "long" });
        return `${weekday}, ${day} ${MONTHS[month - 1]} ${year}`;
    }

    function currency(amount) {
        const value = Number(amount);
        return "Rs. " + value.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    function statusBadgeClass(status) {
        switch ((status || "").toUpperCase()) {
            case "COMPLETED": return "badge badge-completed";
            case "CANCELLED": return "badge badge-cancelled";
            default: return "badge badge-scheduled";
        }
    }

    function statusLabel(status) {
        const s = (status || "").toUpperCase();
        return s.charAt(0) + s.slice(1).toLowerCase();
    }

    return { date, weekdayDate, time, currency, statusBadgeClass, statusLabel };
})();
