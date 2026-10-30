FROM node:22-alpine
WORKDIR /app
COPY --chown=node:node server/package.json ./
COPY --chown=node:node server/src ./src
COPY --chown=node:node LICENSE COPYRIGHT.md ./
RUN mkdir data && chown node:node data
USER node
ENV HOST=0.0.0.0 PORT=8787 DATA_FILE=/app/data/state.enc
EXPOSE 8787
CMD ["node", "src/main.js"]
