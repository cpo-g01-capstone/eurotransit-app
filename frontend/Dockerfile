# EuroTransit frontend — multi-stage: Node build → nginx static serve.
#
# The runtime stage is nginx with deploy/nginx.conf: it serves the SPA on
# port 8080 (the chart-wide appPort) with the 07b security headers (CSP, HSTS,
# nosniff) baked in. No Node, no source, no node_modules in the final image.

FROM node:24-alpine AS build
WORKDIR /app
# Lockfile-exact install (07b supply chain: pinned deps, npm ci not install)
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
# Replace the stock :80 server with our hardened :8080 one
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
# Non-root: write pid to a writable location and fix directory permissions
RUN sed -i 's|/var/run/nginx.pid|/tmp/nginx.pid|' /etc/nginx/nginx.conf \
    && chown -R nginx:nginx /var/cache/nginx /var/log/nginx
USER 101
EXPOSE 8080
