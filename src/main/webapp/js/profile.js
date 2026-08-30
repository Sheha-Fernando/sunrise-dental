(function () {
    document.addEventListener("shell:ready", (e) => {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        const session = e.detail;
        document.getElementById("profileFullName").textContent = session.fullName;
        document.getElementById("profileUsername").textContent = session.username;
        document.getElementById("profileRole").textContent = Shell.roleLabel(session.role);
    });
})();
