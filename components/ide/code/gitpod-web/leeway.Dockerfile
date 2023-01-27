# Copyright (c) 2021 Gitpod GmbH. All rights reserved.
# Licensed under the GNU Affero General Public License (AGPL).
# See License.AGPL.txt in the project root for license information.

FROM gitpod/openvscode-server-linux-build-agent:bionic-x64 as extension_builder

ARG NODE_VERSION=16.19.0
ARG NVM_DIR="/root/.nvm"
RUN mkdir -p $NVM_DIR \
    && curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.1/install.sh | sh \
    && . $NVM_DIR/nvm.sh \
    && nvm alias default $NODE_VERSION
ENV PATH=$NVM_DIR/versions/node/v$NODE_VERSION/bin:$PATH

ARG GITPOD_CODE_COMMIT

RUN mkdir /gitpod-code \
    && cd /gitpod-code \
    && git init \
    && git remote add origin https://github.com/gitpod-io/gitpod-code \
    && git fetch origin $GITPOD_CODE_COMMIT --depth=1 \
    && git reset --hard FETCH_HEAD
WORKDIR /gitpod-code
RUN yarn --frozen-lockfile --network-timeout 180000 \
    && yarn run build:gitpod-web

RUN mkdir /extension \
    && cp -r /gitpod-code/gitpod-web/out /extension \
    && cp -r /gitpod-code/gitpod-web/public /extension \
    && cp -r /gitpod-code/gitpod-web/resources /extension \
    && cp /gitpod-code/gitpod-web/package.json /extension \
    && cp /gitpod-code/gitpod-web/package.nls.json /extension \
    && cp /gitpod-code/gitpod-web/README.md /extension \
    && cp /gitpod-code/gitpod-web/LICENSE.txt /extension


FROM scratch

COPY --from=extension_builder --chown=33333:33333 /extension/ /ide/extensions/gitpod-web/
