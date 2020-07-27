/**
 Copyright (c) 2020 MarkLogic Corporation

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
'use strict';

const Artifacts = require('/data-hub/5/artifacts/core.sjs');
const ds = require("/data-hub/5/data-services/ds-utils.sjs");

var name;
var description;

let existingFlow = null;
try {
  existingFlow = Artifacts.getArtifact('flow', name);
} catch (e) {
  // swallowing error, as we don't want there to be an existing flow with the given name.
}
if (existingFlow) {
  ds.throwBadRequest(`Cannot create flow; flow with the name '${name}' already exists`);
}
const flow = {
  name: name,
  steps: {}
};

if (description) {
  flow.description = description;
}

Artifacts.setArtifact("flow", name, flow);
