# Copyright (c) 2021 Gitpod GmbH. All rights reserved.
# Licensed under the GNU Affero General Public License (AGPL).
# See License.AGPL.txt in the project root for license information.

ARG IMAGE_DIGEST

FROM jeanp413/gitpod-web@${IMAGE_DIGEST} as extension_builder


FROM scratch

COPY --from=extension_builder --chown=33333:33333 /gitpod-web /ide/extensions/
