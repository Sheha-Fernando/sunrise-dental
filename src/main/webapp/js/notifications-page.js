(function () {
    const NOTIF_TYPE_DOT = {
        APPOINTMENT_CREATED: "#2A78D6",
        APPOINTMENT_CANCELLED: "#A6403A",
        APPOINTMENT_RESCHEDULED: "#1D5D95",
        APPOINTMENT_COMPLETED: "#1E7A4B",
        PATIENT_CHECKED_IN: "#1E7A4B",
        BILL_GENERATED: "#B89552",
        PATIENT_RUNNING_LATE: "#B8860B",
        DENTIST_RUNNING_LATE: "#B8860B",
        PATIENT_ARRIVED: "#1E7A4B",
        GENERAL_MESSAGE: "#6B706D",
    };

    let allNotifications = [];

    document.addEventListener("shell:ready", init);

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    function init() {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        document.getElementById("filterType").addEventListener("change", renderFiltered);
        document.getElementById("filterStatus").addEventListener("change", renderFiltered);
        document.getElementById("filterDate").addEventListener("change", renderFiltered);
        document.getElementById("markAllReadBtn").addEventListener("click", markAllRead);

        load();
    }

    async function load() {
        const container = document.getElementById("notifPageListContainer");
        container.innerHTML = `<div class="loading-inline"><span class="spinner"></span> Loading notifications...</div>`;
        try {
            allNotifications = await Api.get("/notifications?limit=200");
            renderFiltered();
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">Unable to load notifications</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
                <button type="button" class="btn btn-secondary" style="margin-top:0.9rem;" id="notifPageRetryBtn">Retry</button>
            </div>`;
            document.getElementById("notifPageRetryBtn").addEventListener("click", load);
        }
    }

    async function markAllRead() {
        try {
            await Api.put("/notifications/read-all");
            allNotifications.forEach(n => { n.isRead = true; });
            renderFiltered();
        } catch (err) {
            // Non-fatal - the list simply stays as it was.
        }
    }

    function todayIso() {
        return new Date().toISOString().split("T")[0];
    }

    function mondayIso() {
        const d = new Date();
        const day = d.getDay();
        const diff = day === 0 ? -6 : 1 - day;
        d.setDate(d.getDate() + diff);
        d.setHours(0, 0, 0, 0);
        return d.toISOString().split("T")[0];
    }

    function renderFiltered() {
        const type = document.getElementById("filterType").value;
        const status = document.getElementById("filterStatus").value;
        const dateFilter = document.getElementById("filterDate").value;
        const today = todayIso();
        const monday = mondayIso();

        let filtered = allNotifications;
        if (type) {
            filtered = filtered.filter(n => n.category === type);
        }
        if (status === "unread") {
            filtered = filtered.filter(n => !n.isRead);
        } else if (status === "read") {
            filtered = filtered.filter(n => n.isRead);
        }
        if (dateFilter) {
            filtered = filtered.filter(n => {
                const notifDate = (n.createdAt || "").split(" ")[0].split("T")[0];
                if (dateFilter === "today") return notifDate === today;
                if (dateFilter === "week") return notifDate >= monday;
                if (dateFilter === "older") return notifDate < monday;
                return true;
            });
        }

        renderList(filtered);
    }

    function renderList(notifications) {
        const container = document.getElementById("notifPageListContainer");
        if (notifications.length === 0) {
            const hasAny = allNotifications.length > 0;
            container.innerHTML = hasAny
                ? `<div class="empty-state"><div class="empty-title">No notifications match your filters</div></div>`
                : `<div class="empty-state">
                     <div class="empty-title">You're all caught up</div>
                     <div class="empty-desc">No new notifications at the moment.</div>
                   </div>`;
            return;
        }

        container.innerHTML = notifications.map(notifRowHtml).join("");
        container.querySelectorAll("[data-notif-id]").forEach(el => {
            el.addEventListener("click", () => handleClick(el.dataset.notifId));
        });
    }

    function notifRowHtml(n) {
        const dotColor = NOTIF_TYPE_DOT[n.type] || "#6B706D";
        const contextParts = [];
        if (n.appointmentNumber) contextParts.push(escapeHtml(n.appointmentNumber));
        if (n.appointmentTime) contextParts.push(escapeHtml(Fmt.time(n.appointmentTime)));
        if (n.dentistName) contextParts.push(escapeHtml(n.dentistName));
        const contextHtml = contextParts.length
            ? `<div class="notif-item-context">${contextParts.join(" &middot; ")}</div>` : "";

        const metaParts = [];
        if (n.senderName) metaParts.push(escapeHtml(n.senderName));
        if (n.createdAt) metaParts.push(escapeHtml(n.createdAt.replace("T", " ").slice(0, 16)));

        return `<div class="notif-item ${n.isRead ? "" : "unread"}" data-notif-id="${n.notificationId}">
            <span class="notif-item-dot" style="background:${dotColor}"></span>
            <div class="notif-item-body">
                <div class="notif-item-title-row">
                    <span class="notif-item-title">${escapeHtml(n.title)}</span>
                    <span class="notif-type-badge">${n.category === "MESSAGE" ? "MESSAGE" : "SYSTEM"}</span>
                </div>
                <div class="notif-item-message">${escapeHtml(n.message)}</div>
                ${contextHtml}
                <div class="notif-item-meta">${metaParts.join(" &middot; ")}</div>
            </div>
        </div>`;
    }

    async function handleClick(notificationId) {
        const notif = allNotifications.find(n => String(n.notificationId) === String(notificationId));
        if (!notif) return;
        if (!notif.isRead) {
            try {
                await Api.put(`/notifications/${notificationId}/read`);
                notif.isRead = true;
                renderFiltered();
            } catch (err) {
                // Non-fatal - still navigate even if marking read failed.
            }
        }
        if (notif.referenceType === "APPOINTMENT" && notif.appointmentNumber) {
            const target = notif.type === "BILL_GENERATED" ? "billing.html" : "search.html";
            window.location.href = target + "?number=" + encodeURIComponent(notif.appointmentNumber);
        }
    }
})();
