/**
 * Renders the sidebar/topbar application shell on every protected page and
 * enforces the frontend-side role gate declared as window.PAGE_ROLES (an
 * array of allowed UserRole names, or omitted/empty to mean "any logged-in
 * role"). This is a UX convenience only - the real authorization check
 * already happened server-side on every API call; this just avoids showing
 * a broken/empty page to a user whose role wouldn't be able to use it.
 * Relies on api.js being loaded first.
 */
const Shell = (() => {

    const NAV_BY_ROLE = {
        ADMIN: [
            { label: "Dashboard", href: "dashboard.html", icon: "◆" },
            { label: "Appointments", href: "appointments.html", icon: "▤" },
            { label: "Patients", href: "patients.html", icon: "▥" },
            { label: "Billing", href: "billing.html", icon: "▧" },
            { label: "Dentists", href: "dentists.html", icon: "◐" },
            { label: "Treatments", href: "treatments.html", icon: "✚" },
            { label: "Staff", href: "staff.html", icon: "▦" },
            { label: "Reports", href: "reports.html", icon: "▣" },
            { label: "Help", href: "help.html", icon: "?" },
        ],
        RECEPTIONIST: [
            { label: "Dashboard", href: "dashboard.html", icon: "◆" },
            { label: "Appointments", href: "appointments.html", icon: "▤" },
            { label: "Patients", href: "patients.html", icon: "▥" },
            { label: "Billing", href: "billing.html", icon: "▧" },
            { label: "Dentist Availability", href: "dentist-availability.html", icon: "◐" },
            { label: "Reports", href: "reports.html", icon: "▣" },
            { label: "Help", href: "help.html", icon: "?" },
        ],
        DENTIST: [
            { label: "Dashboard", href: "dashboard.html", icon: "◆" },
            { label: "My Schedule", href: "appointments.html", icon: "▤" },
            { label: "My Patients", href: "patients.html", icon: "▥" },
            { label: "Reports", href: "reports.html", icon: "▣" },
            { label: "Help", href: "help.html", icon: "?" },
        ],
        BILLING: [
            { label: "Dashboard", href: "dashboard.html", icon: "◆" },
            { label: "Appointments", href: "appointments.html", icon: "▤" },
            { label: "Patients", href: "patients.html", icon: "▥" },
            { label: "Billing", href: "billing.html", icon: "▧" },
            { label: "Reports", href: "reports.html", icon: "▣" },
            { label: "Help", href: "help.html", icon: "?" },
        ],
        CLINICAL_ASSISTANT: [
            { label: "Dashboard", href: "dashboard.html", icon: "◆" },
            { label: "My Schedule", href: "appointments.html", icon: "▤" },
            { label: "My Patients", href: "patients.html", icon: "▥" },
            { label: "Help", href: "help.html", icon: "?" },
        ],
    };

    // Backend role values (ADMIN/RECEPTIONIST/DENTIST/BILLING) are never
    // shown to the user directly - this is the single place that maps them
    // to their human-friendly display label. staff.js and profile.js both
    // call Shell.roleLabel() rather than keeping their own copy.
    const ROLE_LABELS = {
        ADMIN: "Administrator",
        RECEPTIONIST: "Front Desk Reception",
        DENTIST: "Dentist",
        BILLING: "Billing Staff",
        CLINICAL_ASSISTANT: "Clinical Assistant",
    };

    let currentSession = null;

    function currentPageFile() {
        const parts = window.location.pathname.split("/");
        return parts[parts.length - 1];
    }

    function renderShell(session) {
        const items = NAV_BY_ROLE[session.role] || [];
        const page = currentPageFile();

        const navHtml = items.map(item => {
            const active = item.href === page ? " active" : "";
            return `<a href="${item.href}" class="${active.trim()}">` +
                `<span class="nav-icon" aria-hidden="true">${item.icon}</span>` +
                `<span class="nav-label">${item.label}</span></a>`;
        }).join("");

        const initial = (session.fullName || session.username || "?").trim().charAt(0).toUpperCase();
        const roleLabel = ROLE_LABELS[session.role] || session.role;

        const shellHtml = `
            <aside class="sidebar">
                <div class="sidebar-brand">
                    <div class="brand-name">SUNRISE DENTAL</div>
                    <div class="brand-tagline">Clinic Management</div>
                </div>
                <nav class="sidebar-nav" aria-label="Main navigation">${navHtml}</nav>
                <div class="sidebar-footer">
                    <button type="button" class="btn btn-secondary btn-block" id="shellLogoutBtn">Logout</button>
                </div>
            </aside>
            <div class="app-main">
                <header class="topbar">
                    <div class="topbar-search">
                        <svg class="topbar-search-icon" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                            <circle cx="9" cy="9" r="6.25" stroke="currentColor" stroke-width="1.6"/>
                            <line x1="13.6" y1="13.6" x2="17.5" y2="17.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
                        </svg>
                        <input type="search" id="shellSearchInput" placeholder="Search patients, appointments...">
                    </div>
                    <div class="topbar-user">
                        <div class="notif-bell-wrap" id="notifBellWrap">
                            <button type="button" class="notif-bell" id="notifBellBtn" aria-haspopup="true" aria-label="Notifications">
                                &#128276;
                                <span class="notif-badge" id="notifBadge" hidden>0</span>
                            </button>
                            <div class="notif-panel" id="notifPanel" role="region" aria-label="Notifications">
                                <div class="notif-panel-header">
                                    <span class="notif-panel-title">Notifications</span>
                                    <div class="notif-panel-actions">
                                        <button type="button" class="notif-panel-link" id="notifNewMessageBtn">New Message</button>
                                        <button type="button" class="notif-panel-link" id="notifMarkAllBtn">Mark all as read</button>
                                    </div>
                                </div>
                                <div id="notifListContainer"></div>
                                <div class="notif-panel-footer">
                                    <a href="notifications.html" class="notif-panel-link" style="text-decoration:none;">View all notifications</a>
                                </div>
                            </div>
                        </div>
                        <div class="topbar-user-info">
                            <div class="user-name">${escapeHtml(session.fullName || session.username)}</div>
                            <div class="user-role">${escapeHtml(roleLabel)}</div>
                        </div>
                        <div class="topbar-avatar" id="shellAvatar" tabindex="0" role="button" aria-haspopup="true" aria-label="Profile menu">${escapeHtml(initial)}</div>
                        <div class="profile-menu" id="shellProfileMenu">
                            <div class="profile-menu-identity">
                                <div class="user-name">${escapeHtml(session.fullName || session.username)}</div>
                                <div class="user-role">${escapeHtml(roleLabel)}</div>
                            </div>
                            <a href="profile.html">View Profile</a>
                            <a href="help.html">Help &amp; Support</a>
                            <div class="profile-menu-divider"></div>
                            <button type="button" id="shellProfileLogoutBtn">Sign Out</button>
                        </div>
                    </div>
                </header>
                <main class="page-content" id="pageContent"></main>
            </div>
        `;

        const shellRoot = document.getElementById("appShell");
        shellRoot.classList.add("app-shell");
        shellRoot.innerHTML = shellHtml;

        document.getElementById("shellLogoutBtn").addEventListener("click", doLogout);
        document.getElementById("shellProfileLogoutBtn").addEventListener("click", doLogout);

        const avatar = document.getElementById("shellAvatar");
        const menu = document.getElementById("shellProfileMenu");
        avatar.addEventListener("click", () => menu.classList.toggle("open"));
        avatar.addEventListener("keydown", (e) => {
            if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                menu.classList.toggle("open");
            }
        });
        document.addEventListener("click", (e) => {
            if (!e.target.closest(".topbar-user")) {
                menu.classList.remove("open");
            }
        });

        const searchInput = document.getElementById("shellSearchInput");
        searchInput.addEventListener("keydown", (e) => {
            if (e.key === "Enter" && searchInput.value.trim()) {
                window.location.href = "search.html?number=" + encodeURIComponent(searchInput.value.trim());
            }
        });
    }

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    async function doLogout() {
        try {
            await Api.post("/auth/logout", {});
        } catch (err) {
            // Even if the call fails, still return to login.
        }
        window.location.href = Api.ROOT + "index.html";
    }

    function renderAccessDenied() {
        const content = document.getElementById("pageContent");
        content.innerHTML = `
            <div class="access-denied">
                <div class="ad-code">403 &middot; Access Denied</div>
                <h1>You don't have access to this page</h1>
                <p>Your account role doesn't include this section. If you believe this is a mistake, contact your administrator.</p>
                <a href="dashboard.html" class="btn btn-primary">Back to Dashboard</a>
            </div>
        `;
    }

    // ==================== Notifications ====================
    // Loaded on every authenticated page via this shared shell, so every
    // piece here is defensive: a failure must never block the rest of the
    // page (nav, search, profile menu) from working.

    const NOTIF_POLL_MS = 45000;
    let notifPanelOpen = false;

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

    const NOTIF_MESSAGE_TYPES = [
        { value: "PATIENT_RUNNING_LATE", label: "Patient running late" },
        { value: "DENTIST_RUNNING_LATE", label: "Dentist running late" },
        { value: "PATIENT_ARRIVED", label: "Patient arrived" },
        { value: "APPOINTMENT_CANCELLED", label: "Appointment cancelled" },
        { value: "APPOINTMENT_RESCHEDULED", label: "Appointment rescheduled" },
        { value: "GENERAL_MESSAGE", label: "General message" },
    ];

    function notifTimeAgo(mysqlDateTime) {
        if (!mysqlDateTime) return "";
        const then = new Date(mysqlDateTime.replace(" ", "T"));
        const minutes = Math.round((Date.now() - then.getTime()) / 60000);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        const hours = Math.round(minutes / 60);
        if (hours < 24) return hours + (hours === 1 ? " hour ago" : " hours ago");
        const days = Math.round(hours / 24);
        return days + (days === 1 ? " day ago" : " days ago");
    }

    function initNotifications(session) {
        try {
            const wrap = document.getElementById("notifBellWrap");
            if (!wrap) return;

            const bellBtn = document.getElementById("notifBellBtn");
            const panel = document.getElementById("notifPanel");

            bellBtn.addEventListener("click", () => {
                notifPanelOpen = !notifPanelOpen;
                panel.classList.toggle("open", notifPanelOpen);
                if (notifPanelOpen) {
                    loadNotificationList();
                }
            });

            document.addEventListener("click", (e) => {
                if (notifPanelOpen && !e.target.closest("#notifBellWrap")) {
                    notifPanelOpen = false;
                    panel.classList.remove("open");
                }
            });

            document.getElementById("notifMarkAllBtn").addEventListener("click", async () => {
                try {
                    await Api.put("/notifications/read-all");
                    loadNotificationList();
                    refreshUnreadCount();
                } catch (err) {
                    // Non-fatal - the panel simply stays as it was.
                }
            });

            document.getElementById("notifNewMessageBtn").addEventListener("click", () => {
                notifPanelOpen = false;
                panel.classList.remove("open");
                openComposeModal();
            });

            refreshUnreadCount();
            setInterval(refreshUnreadCount, NOTIF_POLL_MS);
        } catch (err) {
            // Never let a notification bug break the shell.
        }
    }

    async function refreshUnreadCount() {
        try {
            const result = await Api.get("/notifications/unread-count");
            const badge = document.getElementById("notifBadge");
            if (!badge) return;
            const count = (result && typeof result.count === "number") ? result.count : 0;
            if (count > 0) {
                badge.textContent = count > 99 ? "99+" : String(count);
                badge.hidden = false;
            } else {
                badge.hidden = true;
            }
        } catch (err) {
            // Leave whatever badge state was already showing.
        }
    }

    let lastLoadedNotifications = [];

    async function loadNotificationList() {
        const container = document.getElementById("notifListContainer");
        if (!container) return;
        container.innerHTML = `<div class="loading-inline" style="padding:1.2rem;"><span class="spinner"></span> Loading...</div>`;
        try {
            lastLoadedNotifications = await Api.get("/notifications?limit=8");
            renderNotificationList(container, lastLoadedNotifications);
        } catch (err) {
            container.innerHTML = `<div class="notif-empty">
                <div class="notif-empty-title">Unable to load notifications</div>
                <button type="button" class="btn btn-secondary btn-sm" id="notifRetryBtn" style="margin-top:0.6rem;">Retry</button>
            </div>`;
            const retryBtn = document.getElementById("notifRetryBtn");
            if (retryBtn) retryBtn.addEventListener("click", loadNotificationList);
        }
    }

    function renderNotificationList(container, notifications) {
        if (!notifications || notifications.length === 0) {
            container.innerHTML = `<div class="notif-empty">
                <div class="notif-empty-title">You're all caught up</div>
                <div>No new notifications at the moment.</div>
            </div>`;
            return;
        }
        container.innerHTML = notifications.map(notifItemHtml).join("");
        container.querySelectorAll("[data-notif-id]").forEach(el => {
            el.addEventListener("click", () => handleNotificationClick(el.dataset.notifId));
        });
    }

    function notifItemHtml(n) {
        const dotColor = NOTIF_TYPE_DOT[n.type] || "#6B706D";
        const contextParts = [];
        if (n.appointmentNumber) contextParts.push(escapeHtml(n.appointmentNumber));
        if (n.appointmentTime) contextParts.push(escapeHtml(Fmt.time(n.appointmentTime)));
        if (n.dentistName) contextParts.push(escapeHtml(n.dentistName));
        const contextHtml = contextParts.length
            ? `<div class="notif-item-context">${contextParts.join(" &middot; ")}</div>` : "";

        const metaParts = [];
        if (n.senderName) metaParts.push(escapeHtml(n.senderName));
        metaParts.push(notifTimeAgo(n.createdAt));

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

    async function handleNotificationClick(notificationId) {
        const notif = lastLoadedNotifications.find(n => String(n.notificationId) === String(notificationId));
        if (!notif) return;
        if (!notif.isRead) {
            try {
                await Api.put(`/notifications/${notificationId}/read`);
                refreshUnreadCount();
            } catch (err) {
                // Non-fatal - still navigate even if marking read failed.
            }
        }
        notifPanelOpen = false;
        document.getElementById("notifPanel")?.classList.remove("open");

        if (notif.referenceType === "APPOINTMENT" && notif.appointmentNumber) {
            const target = notif.type === "BILL_GENERATED" ? "billing.html" : "search.html";
            window.location.href = target + "?number=" + encodeURIComponent(notif.appointmentNumber);
        }
    }

    // --- New Message compose modal (injected once, on demand) ---

    function ensureComposeModal() {
        if (document.getElementById("notifComposeOverlay")) return;
        document.body.insertAdjacentHTML("beforeend", `
            <div class="modal-overlay" id="notifComposeOverlay">
                <div class="modal" style="max-width:480px;" role="dialog" aria-modal="true" aria-labelledby="notifComposeTitle">
                    <button type="button" class="modal-close" id="notifComposeClose" aria-label="Close">&times;</button>
                    <h2 id="notifComposeTitle">New Message</h2>
                    <div class="form-group">
                        <label for="notifType">Message Type</label>
                        <select id="notifType">
                            ${NOTIF_MESSAGE_TYPES.map(t => `<option value="${t.value}">${t.label}</option>`).join("")}
                        </select>
                    </div>
                    <div class="form-group">
                        <label for="notifAppointment">Related Appointment (optional)</label>
                        <select id="notifAppointment"><option value="">None</option></select>
                    </div>
                    <div class="form-group">
                        <label>Send To</label>
                        <div class="notif-recipient-list" id="notifRecipientList"></div>
                    </div>
                    <div class="form-group">
                        <label for="notifMessageText">Message</label>
                        <textarea id="notifMessageText" rows="3" placeholder="e.g. Patient has informed the clinic that she will arrive approximately 10 minutes late."></textarea>
                    </div>
                    <p class="field-error" id="notifComposeError"></p>
                    <div class="btn-row">
                        <button type="button" class="btn btn-secondary" id="notifComposeCancel">Cancel</button>
                        <button type="button" class="btn btn-primary" id="notifComposeSend">Send Message</button>
                    </div>
                </div>
            </div>`);

        document.getElementById("notifComposeClose").addEventListener("click", closeComposeModal);
        document.getElementById("notifComposeCancel").addEventListener("click", closeComposeModal);
        document.getElementById("notifComposeOverlay").addEventListener("click", (e) => {
            if (e.target.id === "notifComposeOverlay") closeComposeModal();
        });
        document.getElementById("notifComposeSend").addEventListener("click", submitComposeMessage);
    }

    function closeComposeModal() {
        document.getElementById("notifComposeOverlay")?.classList.remove("open");
    }

    async function openComposeModal() {
        try {
            ensureComposeModal();
            const errorEl = document.getElementById("notifComposeError");
            errorEl.textContent = "";
            errorEl.classList.remove("visible");
            document.getElementById("notifMessageText").value = "";
            document.getElementById("notifType").value = "PATIENT_RUNNING_LATE";

            const apptSelect = document.getElementById("notifAppointment");
            apptSelect.innerHTML = '<option value="">None</option>';
            const recipientContainer = document.getElementById("notifRecipientList");
            recipientContainer.innerHTML = `<div class="loading-inline"><span class="spinner"></span> Loading...</div>`;

            document.getElementById("notifComposeOverlay").classList.add("open");

            const [appointments, recipients] = await Promise.all([
                Api.get("/appointments").catch(() => []),
                Api.get("/notifications/recipients").catch(() => []),
            ]);

            const relevant = appointments
                .filter(a => a.status === "SCHEDULED")
                .sort((a, b) => (a.appointmentDate + a.appointmentTime).localeCompare(b.appointmentDate + b.appointmentTime))
                .slice(0, 50);
            apptSelect.innerHTML = '<option value="">None</option>' + relevant.map(a =>
                `<option value="${escapeHtml(a.appointmentNumber)}">${escapeHtml(a.appointmentNumber)} &middot; `
                + `${escapeHtml(a.patientName)} &middot; ${escapeHtml(Fmt.time(a.appointmentTime))} &middot; `
                + `${escapeHtml(a.dentistName)}</option>`
            ).join("");

            if (recipients.length === 0) {
                recipientContainer.innerHTML = `<div style="color:var(--color-text-muted);font-size:0.85rem;">No eligible recipients found.</div>`;
            } else {
                recipientContainer.innerHTML = recipients.map(r => `
                    <label class="notif-recipient-item">
                        <input type="checkbox" value="${r.userId}">
                        ${escapeHtml(r.fullName)} <span style="color:var(--color-text-faint);">&middot; ${escapeHtml(ROLE_LABELS[r.role] || r.role)}</span>
                    </label>`).join("");
            }
        } catch (err) {
            // The modal stays open with whatever partial state it has - the
            // Send button will simply fail validation if nothing loaded.
        }
    }

    async function submitComposeMessage() {
        const errorEl = document.getElementById("notifComposeError");
        errorEl.textContent = "";
        errorEl.classList.remove("visible");

        const type = document.getElementById("notifType").value;
        const appointmentNumber = document.getElementById("notifAppointment").value;
        const message = document.getElementById("notifMessageText").value.trim();
        const recipientIds = [...document.querySelectorAll("#notifRecipientList input[type=checkbox]:checked")]
            .map(cb => cb.value);

        if (!message) {
            errorEl.textContent = "Message is required.";
            errorEl.classList.add("visible");
            return;
        }
        if (recipientIds.length === 0) {
            errorEl.textContent = "Select at least one recipient.";
            errorEl.classList.add("visible");
            return;
        }

        const sendBtn = document.getElementById("notifComposeSend");
        sendBtn.disabled = true;
        sendBtn.textContent = "Sending...";
        try {
            await Api.post("/notifications/messages", {
                type,
                appointmentNumber,
                message,
                recipientUserIds: recipientIds.join(","),
            });
            closeComposeModal();
            loadNotificationList();
            refreshUnreadCount();
        } catch (err) {
            errorEl.textContent = err.message || "Unable to send this message right now.";
            errorEl.classList.add("visible");
        } finally {
            sendBtn.disabled = false;
            sendBtn.textContent = "Send Message";
        }
    }

    async function init() {
        try {
            const session = await Api.get("/auth/session");
            currentSession = session;
            renderShell(session);
            initNotifications(session);

            const allowed = window.PAGE_ROLES;
            if (Array.isArray(allowed) && allowed.length > 0 && !allowed.includes(session.role)) {
                renderAccessDenied();
                document.dispatchEvent(new CustomEvent("shell:denied"));
                return;
            }
            document.dispatchEvent(new CustomEvent("shell:ready", { detail: session }));
        } catch (err) {
            // Api.get already redirects to login on 401.
        }
    }

    document.addEventListener("DOMContentLoaded", init);

    // Accessibility: Escape closes whatever modal or profile menu is open,
    // consistent across every page that uses the shared .modal-overlay pattern.
    document.addEventListener("keydown", (e) => {
        if (e.key !== "Escape") {
            return;
        }
        const openModal = document.querySelector(".modal-overlay.open");
        if (openModal) {
            openModal.classList.remove("open");
            return;
        }
        const menu = document.getElementById("shellProfileMenu");
        if (menu && menu.classList.contains("open")) {
            menu.classList.remove("open");
        }
    });

    return {
        session: () => currentSession,
        roleLabel: (role) => ROLE_LABELS[role] || role,
    };
})();
