(function () {
    const DAY_LABELS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

    let currentProfile = null;

    document.addEventListener("shell:ready", () => {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        document.getElementById("openEditProfileBtn").addEventListener("click", openEditModal);
        wireEditProfileModal();
        wireChangePasswordModal();

        loadProfile();
    });

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    function initialsOf(fullName) {
        const parts = (fullName || "").trim().replace(/^dr\.?\s+/i, "").split(/\s+/).filter(Boolean);
        if (parts.length === 0) return "?";
        return (parts[0].charAt(0) + (parts.length > 1 ? parts[parts.length - 1].charAt(0) : "")).toUpperCase();
    }

    function displayOrFallback(value) {
        return value ? escapeHtml(value) : "Not available";
    }

    async function loadProfile() {
        const loading = document.getElementById("profileLoading");
        const contentEl = document.getElementById("profileContent");
        const alertEl = document.getElementById("profileAlert");
        alertEl.classList.remove("visible");
        loading.hidden = false;
        contentEl.hidden = true;

        try {
            currentProfile = await Api.get("/profile");
            renderProfile(currentProfile);
            loading.hidden = true;
            contentEl.hidden = false;
            document.getElementById("openEditProfileBtn").hidden = false;
        } catch (err) {
            loading.hidden = true;
            alertEl.textContent = err.message || "We couldn't load your profile right now.";
            alertEl.classList.add("visible");
        }
    }

    function renderProfile(profile) {
        document.getElementById("profileAvatar").textContent = initialsOf(profile.fullName);
        document.getElementById("profileFullName").textContent = profile.fullName || "";
        document.getElementById("profileRole").textContent = Shell.roleLabel(profile.role);

        document.getElementById("profileUsername").textContent = profile.username || "";
        document.getElementById("profileContact").textContent = displayOrFallback(profile.contactNumber);
        document.getElementById("profileEmail").textContent = displayOrFallback(profile.email);
        document.getElementById("profileLastLogin").textContent = displayOrFallback(profile.lastLogin);

        renderRoleSection(profile);
    }

    function renderRoleSection(profile) {
        const section = document.getElementById("profileRoleSection");
        if (profile.role === "DENTIST") {
            section.innerHTML = `
                <div class="detail-item">
                    <div class="detail-label">Specialty</div>
                    <div class="detail-value">${displayOrFallback(profile.specialty)}</div>
                </div>
                <div class="detail-item" style="margin-top:0.9rem;">
                    <div class="detail-label">Working Days</div>
                    ${workingDaysHtml(profile.workingDays)}
                </div>`;
            section.hidden = false;
        } else if (profile.role === "CLINICAL_ASSISTANT") {
            section.innerHTML = `
                <div class="detail-item">
                    <div class="detail-label">Assisting</div>
                    <div class="profile-assisting-row">
                        <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
                            <path d="M5 3v5a4 4 0 0 0 8 0V3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
                            <circle cx="15.5" cy="12.5" r="1.8" stroke="currentColor" stroke-width="1.4"/>
                            <path d="M9 8v2.5a4.5 4.5 0 0 0 4.5 4.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
                        </svg>
                        ${profile.assignedDentistName ? "Assisting " + escapeHtml(profile.assignedDentistName) : "Not available"}
                    </div>
                </div>`;
            section.hidden = false;
        } else {
            section.hidden = true;
            section.innerHTML = "";
        }
    }

    function workingDaysHtml(workingDays) {
        const pattern = (workingDays || "0000000").split("").map(c => c === "1");
        const days = pattern.map((isWorking, i) => `
            <div class="profile-day">
                <span class="profile-day-circle ${isWorking ? "working" : "off"}"></span>
                ${DAY_LABELS[i]}
            </div>`).join("");
        return `<div class="profile-working-days">${days}</div>`;
    }

    // ------------------------------------------------------------ edit profile ----

    function setFieldError(inputId, errorId, show) {
        const input = document.getElementById(inputId);
        const error = document.getElementById(errorId);
        if (show) {
            input.setAttribute("aria-invalid", "true");
            error.classList.add("visible");
        } else {
            input.removeAttribute("aria-invalid");
            error.classList.remove("visible");
        }
    }

    function openEditModal() {
        if (!currentProfile) return;
        document.getElementById("editProfileAlert").classList.remove("visible");
        ["editFullName,editFullNameError", "editUsername,editUsernameError",
            "editContact,editContactError", "editEmail,editEmailError"].forEach(pair => {
            const [inputId, errorId] = pair.split(",");
            setFieldError(inputId, errorId, false);
        });

        document.getElementById("editFullName").value = currentProfile.fullName || "";
        document.getElementById("editUsername").value = currentProfile.username || "";
        document.getElementById("editContact").value = currentProfile.contactNumber || "";
        document.getElementById("editEmail").value = currentProfile.email || "";

        document.getElementById("editProfileModalOverlay").classList.add("open");
        document.getElementById("editFullName").focus();
    }

    function closeEditModal() {
        document.getElementById("editProfileModalOverlay").classList.remove("open");
    }

    function wireEditProfileModal() {
        document.getElementById("closeEditProfileModal").addEventListener("click", closeEditModal);
        document.getElementById("cancelEditProfileBtn").addEventListener("click", closeEditModal);
        document.getElementById("editProfileModalOverlay").addEventListener("click", (e) => {
            if (e.target.id === "editProfileModalOverlay") closeEditModal();
        });

        document.getElementById("editProfileForm").addEventListener("submit", async (event) => {
            event.preventDefault();
            const fullName = document.getElementById("editFullName");
            const username = document.getElementById("editUsername");

            let valid = true;
            if (fullName.value.trim() === "") {
                setFieldError("editFullName", "editFullNameError", true);
                valid = false;
            }
            if (username.value.trim() === "") {
                setFieldError("editUsername", "editUsernameError", true);
                valid = false;
            }
            if (!valid) return;

            const submitBtn = document.getElementById("editProfileSubmitBtn");
            submitBtn.disabled = true;
            submitBtn.textContent = "Saving...";
            const alertEl = document.getElementById("editProfileAlert");
            alertEl.classList.remove("visible");

            const params = new URLSearchParams({
                fullName: fullName.value.trim(),
                username: username.value.trim(),
                contactNumber: document.getElementById("editContact").value.trim(),
                email: document.getElementById("editEmail").value.trim(),
            });

            try {
                const updated = await Api.put(`/profile?${params.toString()}`);
                currentProfile = updated;
                renderProfile(currentProfile);
                closeEditModal();
                Toast.success("Profile updated successfully.");
            } catch (err) {
                alertEl.textContent = err.message;
                alertEl.classList.add("visible");
            } finally {
                submitBtn.disabled = false;
                submitBtn.textContent = "Save Changes";
            }
        });
    }

    // ------------------------------------------------------- change password ----

    function openChangePasswordModal() {
        document.getElementById("changePasswordAlert").classList.remove("visible");
        document.getElementById("changePasswordForm").reset();
        ["currentPassword,currentPasswordError", "newPassword,newPasswordError",
            "confirmNewPassword,confirmNewPasswordError"].forEach(pair => {
            const [inputId, errorId] = pair.split(",");
            setFieldError(inputId, errorId, false);
        });
        document.getElementById("changePasswordModalOverlay").classList.add("open");
        document.getElementById("currentPassword").focus();
    }

    function closeChangePasswordModal() {
        document.getElementById("changePasswordModalOverlay").classList.remove("open");
    }

    function wireChangePasswordModal() {
        document.getElementById("openChangePasswordBtn").addEventListener("click", openChangePasswordModal);
        document.getElementById("closeChangePasswordModal").addEventListener("click", closeChangePasswordModal);
        document.getElementById("cancelChangePasswordBtn").addEventListener("click", closeChangePasswordModal);
        document.getElementById("changePasswordModalOverlay").addEventListener("click", (e) => {
            if (e.target.id === "changePasswordModalOverlay") closeChangePasswordModal();
        });

        document.getElementById("changePasswordForm").addEventListener("submit", async (event) => {
            event.preventDefault();
            const current = document.getElementById("currentPassword");
            const next = document.getElementById("newPassword");
            const confirm = document.getElementById("confirmNewPassword");

            let valid = true;
            if (current.value.trim() === "") {
                setFieldError("currentPassword", "currentPasswordError", true);
                valid = false;
            }
            if (next.value.length < 6) {
                setFieldError("newPassword", "newPasswordError", true);
                valid = false;
            }
            if (confirm.value !== next.value || confirm.value === "") {
                setFieldError("confirmNewPassword", "confirmNewPasswordError", true);
                valid = false;
            }
            if (!valid) return;

            const submitBtn = document.getElementById("changePasswordSubmitBtn");
            submitBtn.disabled = true;
            submitBtn.textContent = "Changing...";
            const alertEl = document.getElementById("changePasswordAlert");
            alertEl.classList.remove("visible");

            const params = new URLSearchParams({
                currentPassword: current.value,
                newPassword: next.value,
            });

            try {
                await Api.put(`/profile/password?${params.toString()}`);
                closeChangePasswordModal();
                Toast.success("Password changed successfully.");
            } catch (err) {
                alertEl.textContent = err.message;
                alertEl.classList.add("visible");
            } finally {
                submitBtn.disabled = false;
                submitBtn.textContent = "Change Password";
            }
        });
    }
})();
