// 跨平台静态检查：确保新增扩展真正加入 Xcode target、编译源文件与 IPA 嵌入链路。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ios = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = fs.readFileSync(path.join(ios, 'iosApp.xcodeproj/project.pbxproj'), 'utf8');
const tokens = source.match(/\/\*[\s\S]*?\*\/|\/\/[^\r\n]*|"(?:\\.|[^"\\])*"|[{}()=;,]|[^\s{}()=;,]+/g)
    .filter(t => !t.startsWith('/*') && !t.startsWith('//'));
let cursor = 0;
const take = () => tokens[cursor++];
const expect = t => assert.equal(take(), t);
function value() {
    const t = take();
    if (t === '{') {
        const result = {};
        while (tokens[cursor] !== '}') {
            const key = scalar(take());
            assert.ok(!(key in result), `duplicate key: ${key}`);
            expect('=');
            result[key] = value();
            expect(';');
        }
        expect('}');
        return result;
    }
    if (t === '(') {
        const result = [];
        while (tokens[cursor] !== ')') {
            result.push(value());
            if (tokens[cursor] === ',') take();
            else assert.equal(tokens[cursor], ')');
        }
        expect(')');
        return result;
    }
    return scalar(t);
}
function scalar(t) {
    assert.ok(t !== undefined, 'unexpected end of project');
    return t.startsWith('"') ? JSON.parse(t) : t;
}
const project = value();
assert.equal(cursor, tokens.length);
const objects = project.objects;
const root = objects[project.rootObject];
const targets = root.targets.map(id => ({ id, ...objects[id] }));
const app = targets.find(t => t.name === 'iosApp');
const widget = targets.find(t => t.name === 'IncomeWidget');
assert.ok(app && widget);
assert.equal(widget.productType, 'com.apple.product-type.app-extension');
assert.ok(app.dependencies.some(id => objects[id].target === widget.id));
assert.ok(app.buildPhases.some(id => {
    const phase = objects[id];
    return phase.isa === 'PBXCopyFilesBuildPhase' && phase.dstSubfolderSpec === '13'
        && phase.files.some(file => objects[file].fileRef === widget.productReference);
}));
for (const target of [app, widget]) {
    const groups = target.fileSystemSynchronizedGroups.map(id => objects[id]);
    assert.ok(groups.some(g => g.path === 'WidgetShared'));
    for (const group of groups) assert.ok(fs.existsSync(path.join(ios, group.path)));
    const configs = objects[target.buildConfigurationList].buildConfigurations.map(id => objects[id].buildSettings);
    assert.equal(configs.length, 2);
    for (const config of configs) {
        assert.equal(config.CODE_SIGN_ENTITLEMENTS, 'Configuration/IncomeWidget.entitlements');
        assert.ok(fs.existsSync(path.join(ios, config.INFOPLIST_FILE)));
        if (target === widget) {
            assert.equal(config.PRODUCT_BUNDLE_IDENTIFIER, '$(APP_BUNDLE_IDENTIFIER).IncomeWidget');
            assert.equal(config.APPLICATION_EXTENSION_API_ONLY, 'YES');
            assert.equal(config.SKIP_INSTALL, 'YES');
        }
    }
}
for (const file of ['ios.yml', 'build-test.yml', 'release.yml']) {
    const workflow = fs.readFileSync(path.join(ios, '..', '.github/workflows', file), 'utf8');
    assert.ok(workflow.includes('swift test --package-path iosApp'));
    assert.ok(workflow.includes('bash iosApp/scripts/prepare-widget.sh "$APP_PATH"'));
}
console.log('Xcode project syntax, widget embedding, shared sources, signing and all 3 CI entry points: OK');
