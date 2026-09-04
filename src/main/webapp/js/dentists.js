(function () {
    const DAY_LETTERS = ["S", "M", "T", "W", "T", "F", "S"];

    // Working-day pattern per dentist, Sunday through Saturday. Not yet a
    // stored column in the database - add a new dentist's own 7-item array
    // here (falls back to a Mon-Fri default below if a name isn't listed).
    const WORKING_DAYS = {
        "Dr. Nimal Perera": [false, true, true, true, true, true, true],
        "Dr. Anusha Fernando": [false, true, true, true, true, true, false],
        "Dr. Kasun Silva": [false, false, true, true, true, true, true],
        "Dr. Tharushi Jayawardena": [true, false, false, true, true, true, true],
        "Dr. Ruwan Bandara": [false, true, false, true, false, true, false],
    };
    const DEFAULT_WORKING_DAYS = [false, true, true, true, true, true, false];

    function workingDaysHtml(dentistName) {
        const pattern = WORKING_DAYS[dentistName] || DEFAULT_WORKING_DAYS;
        const circles = pattern.map((isWorking, i) =>
            `<span class="working-day-circle ${isWorking ? "working" : "off"}">${DAY_LETTERS[i]}</span>`).join("");
        return `<div class="working-days-row">${circles}</div>`;
    }

    document.addEventListener("shell:ready", init);

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    function init() {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));
        load();
    }

    async function load() {
        const container = document.getElementById("listContainer");
        try {
            const [dentists, appointments] = await Promise.all([
                Api.get("/dentists"),
                Api.get("/appointments").catch(() => []),
            ]);

            if (dentists.length === 0) {
                container.innerHTML = `<div class="empty-state"><div class="empty-title">No dentists found</div></div>`;
                return;
            }

            const countByName = new Map();
            for (const a of appointments) {
                countByName.set(a.dentistName, (countByName.get(a.dentistName) || 0) + 1);
            }

            const rows = dentists.map(d => `
                <tr>
                    <td class="table-primary-text">${escapeHtml(d.dentistName)}</td>
                    <td class="table-muted-text">${escapeHtml(d.specialty || "&mdash;")}</td>
                    <td>
                        <div class="table-muted-text">${escapeHtml(d.contactNumber || "&mdash;")}</div>
                        ${d.email ? `<div class="table-muted-text"><a href="mailto:${escapeHtml(d.email)}">${escapeHtml(d.email)}</a></div>` : ""}
                    </td>
                    <td>${workingDaysHtml(d.dentistName)}</td>
                    <td>${countByName.get(d.dentistName) || 0}</td>
                    <td><span class="badge badge-active">Active</span></td>
                </tr>`).join("");
            container.innerHTML = `<div class="table-wrap"><table class="data-table">
                <thead><tr><th>Name</th><th>Specialty</th><th>Contact</th><th>Working Days</th><th>Appointments</th><th>Status</th></tr></thead>
                <tbody>${rows}</tbody>
            </table></div>`;
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load dentists</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
    }
})();
