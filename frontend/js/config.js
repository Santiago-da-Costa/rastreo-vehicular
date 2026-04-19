(function () {
    const apiBaseUrls = {
        local: "http://localhost:8000",
        render: "https://rastreo-vehicular.onrender.com"
    };

    const defaultApiBaseUrl = apiBaseUrls.render;

    const configuredApiBaseUrl = window.RASTREO_API_BASE_URL || defaultApiBaseUrl;
    const apiBaseUrl = configuredApiBaseUrl.trim().replace(/\/+$/, "");

    window.RastreoConfig = {
        apiBaseUrl
    };
})();