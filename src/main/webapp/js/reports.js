(function () {
    document.addEventListener("shell:ready", init);

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    function statCard(label, value, accent) {
        return `<div class="stat-card">
            <div class="stat-label">${label}</div>
            <div class="stat-value${accent ? " accent" : ""}">${value}</div>
        </div>`;
    }

    function init() {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));
        load();
    }

    async function load() {
        try {
            const [appointments, bills] = await Promise.all([
                Api.get("/appointments"),
                Api.get("/bills"),
            ]);

            const completed = appointments.filter(a => a.status === "COMPLETED").length;
            const cancelled = appointments.filter(a => a.status === "CANCELLED").length;
            const scheduled = appointments.filter(a => a.status === "SCHEDULED").length;

            document.getElementById("appointmentStats").innerHTML =
                statCard("Total Appointments", appointments.length) +
                statCard("Scheduled", scheduled) +
                statCard("Completed", completed) +
                statCard("Cancelled", cancelled);

            const totalRevenue = bills.reduce((sum, b) => sum + Number(b.totalAmount), 0);
            document.getElementById("revenueStats").innerHTML =
                statCard("Total Revenue", Fmt.currency(totalRevenue), true) +
                statCard("Bills Generated", bills.length) +
                statCard("Average Bill", bills.length ? Fmt.currency(totalRevenue / bills.length) : Fmt.currency(0));

            const treatmentCounts = new Map();
            for (const a of appointments) {
                treatmentCounts.set(a.treatmentType, (treatmentCounts.get(a.treatmentType) || 0) + 1);
            }
            const sorted = [...treatmentCounts.entries()].sort((a, b) => b[1] - a[1]);

            const container = document.getElementById("popularTreatments");
            if (sorted.length === 0) {
                container.innerHTML = `<div class="empty-state"><div class="empty-desc">No appointment data available.</div></div>`;
                return;
            }
            const rows = sorted.map(([name, count]) => `
                <tr><td class="table-primary-text">${escapeHtml(name)}</td><td>${count}</td></tr>`).join("");
            container.innerHTML = `<div class="table-wrap"><table class="data-table">
                <thead><tr><th>Treatment</th><th>Appointments</th></tr></thead>
                <tbody>${rows}</tbody>
            </table></div>`;
        } catch (err) {
            document.getElementById("appointmentStats").innerHTML = "";
            document.getElementById("popularTreatments").innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load reports</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
    }
})();
