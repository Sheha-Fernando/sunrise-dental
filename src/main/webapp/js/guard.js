/**
 * Included on every protected page (dashboard, appointment, search, billing, help).
 * Confirms the backend session is still valid before the page is usable,
 * shows the logged-in staff name in the header, and wires the Logout button.
 * Relies on api.js being loaded first.
 */
(function () {
    async function guardPage() {
        try {
            const session = await Api.get("/auth/session");
            const staffNameEl = document.getElementById("staffName");
            if (staffNameEl) {
                staffNameEl.textContent = session.fullName || session.username;
            }
        } catch (err) {
            // Api.get already redirects to login on 401; nothing else to do here.
        }
    }

    function wireLogout() {
        const logoutBtn = document.getElementById("logoutBtn");
        if (!logoutBtn) {
            return;
        }
        logoutBtn.addEventListener("click", async () => {
            logoutBtn.disabled = true;
            try {
                await Api.post("/auth/logout", {});
            } catch (err) {
                // Even if the logout call fails, still send the user back to login.
            }
            window.location.href = Api.ROOT + "index.html";
        });
    }

    document.addEventListener("DOMContentLoaded", () => {
        guardPage();
        wireLogout();
    });
})();
