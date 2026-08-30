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
            const treatments = await Api.get("/treatments");
            if (treatments.length === 0) {
                container.innerHTML = `<div class="empty-state"><div class="empty-title">No treatments found</div></div>`;
                return;
            }
            const rows = treatments.map(t => `
                <tr>
                    <td class="table-primary-text">${escapeHtml(t.treatmentName)}</td>
                    <td>${Fmt.currency(t.cost)}</td>
                    <td><span class="badge badge-active">Active</span></td>
                </tr>`).join("");
            container.innerHTML = `<div class="table-wrap"><table class="data-table">
                <thead><tr><th>Treatment</th><th>Price</th><th>Status</th></tr></thead>
                <tbody>${rows}</tbody>
            </table></div>`;
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load treatments</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
    }
})();
