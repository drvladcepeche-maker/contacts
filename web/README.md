# Мои контакты — веб-интерфейс

Mobile-first browser UI for viewing and searching cached contacts from the existing Supabase MCP endpoint. It also supports queuing create, update, delete and merge requests. Every queued change must be confirmed on the Android phone before it is applied.

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

Vite outputs a static site into `web/dist`. The GitHub Actions workflow in `.github/workflows/deploy-web.yml` builds and deploys the site when changes are pushed to `free-web-ui`, or on manual workflow dispatch. To permit hosting on GitHub Free, the repository must be public and GitHub Pages must be enabled with GitHub Actions as the deployment source. GitHub Pages sites are publicly reachable, so do not place contact data or tokens in the repository.

## Security and limitations

- The bearer token is held only in the current page's JavaScript memory; it is not written to localStorage, cookies, URLs, or source files.
- Do not publish the token in source code, repository files, or screenshots.
- Create, update, delete and merge requests are queued for the selected Android device. The phone must approve each operation before applying it. Delete and merge also require confirmation in the web UI.
- Data may be stale if the Android app has not synced recently.
- The endpoint uses bearer-token validation and CORS. Do not disable authentication to work around connection errors.
- This is a static browser app, not a ChatGPT MCP entitlement workaround. It does not make custom MCP connections available on the ChatGPT Free plan.
- Before exposing this UI to other people, add individual sign-in/session controls and rate limiting. Never embed a shared administrative token in the frontend.
