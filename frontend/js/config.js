(function () {
    const configuredApiBaseUrl =
        window.RASTREO_API_BASE_URL
        || document.querySelector("meta[name='rastreo-api-base-url']")?.content
        || "";

    const apiBaseUrl = configuredApiBaseUrl.trim().replace(/\/+$/, "") || window.location.origin;

    window.RastreoConfig = {
        apiBaseUrl
    };
})();
