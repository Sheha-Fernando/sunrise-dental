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
            { label: "Help", href: "help.html", icon: "?" },
        ],
        DENTIST: [
            { label: "Dashboard", href: "dashboard.html", icon: "◆" },
            { label: "My Schedule", href: "appointments.html", icon: "▤" },
            { label: "My Patients", href: "patients.html", icon: "▥" },
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
                        <input type="search" id="shellSearchInput" placeholder="Search patients, appointments...">
                    </div>
                    <div class="topbar-user">
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

    async function init() {
        try {
            const session = await Api.get("/auth/session");
            currentSession = session;
            renderShell(session);

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
