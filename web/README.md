# Мои контакты — веб-интерфейс

Mobile-first browser UI for viewing and searching cached contacts from the existing Supabase MCP endpoint.

## Local development

```sh
cd web
npm install
npm run dev
```

## Build and deployment

```sh
npm run build
```

Vite outputs a static site into `web/dist`. The GitHub Actions workflow in `.github/workflows/deploy-web.yml` builds and deploys the site when changes are pushed to `free-web-ui`, or on manual workflow dispatch. GitHub Pages must be enabled for the repository with GitHub Actions as the deployment source.

## Security and limitations

- The bearer token is held only in the current page's JavaScript memory; it is not written to localStorage, cookies, URLs, or source files.
- Do not publish the token in source code, repository files, or screenshots.
- This version supports connecting, listing paired Android devices, reading cached contacts, and searching. Contact modification is intentionally not exposed in the browser UI yet.
- Data may be stale if the Android app has not synced recently.
- The endpoint currently permits CORS requests. Keep bearer-token validation enabled; do not disable authentication to work around connection errors.
- This is a static browser app, not a ChatGPT MCP entitlement workaround. It does not make custom MCP connections available on the ChatGPT Free plan.
- Before exposing this UI to other people or adding contact-write features, add an individual sign-in/session model and rate limiting. Never embed a shared administrative token in the frontend.
