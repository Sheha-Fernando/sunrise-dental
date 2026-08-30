(function () {
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
                    <td class="table-muted-text">${escapeHtml(d.contactNumber || "&mdash;")}</td>
                    <td>${countByName.get(d.dentistName) || 0}</td>
                    <td><span class="badge badge-active">Active</span></td>
                </tr>`).join("");
            container.innerHTML = `<div class="table-wrap"><table class="data-table">
                <thead><tr><th>Name</th><th>Contact</th><th>Appointments</th><th>Status</th></tr></thead>
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
