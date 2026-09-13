import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  applyBase,
  isPlaygroundPath,
  localeFromLanguageTag,
  localeFromSitePath,
  localizeSitePath,
  removeBase,
  resolvePreferredLocale,
} from './language-preference.ts';

describe('language preference', () => {
  it('maps supported language tags to website locales', () => {
    assert.equal(localeFromLanguageTag('zh-CN'), 'zh');
    assert.equal(localeFromLanguageTag('zh-Hant'), 'zh');
    assert.equal(localeFromLanguageTag('en-US'), 'en');
    assert.equal(localeFromLanguageTag('fr-FR'), null);
  });

  it('prioritizes a stored choice over browser languages', () => {
    assert.equal(resolvePreferredLocale('en', ['zh-CN']), 'en');
    assert.equal(resolvePreferredLocale('zh', ['en-US']), 'zh');
  });

  it('uses the first supported browser language and falls back to English', () => {
    assert.equal(resolvePreferredLocale(null, ['fr-FR', 'zh-CN']), 'zh');
    assert.equal(resolvePreferredLocale('invalid', ['ja-JP']), 'en');
  });
});

describe('localized paths', () => {
  it('preserves explicit playground locale URLs on initial load', () => {
    for (const route of ['/playground', '/playground/', '/playground/index.html', '/zh/playground/']) {
      assert.equal(isPlaygroundPath(route), true);
    }
    for (const route of ['/', '/zh/', '/guide/priv-ui', '/playground/other']) {
      assert.equal(isPlaygroundPath(route), false);
    }
    assert.equal(localizeSitePath('/playground/', 'zh'), '/zh/playground/');
    assert.equal(localizeSitePath('/zh/playground/', 'en'), '/playground/');
    assert.equal(isPlaygroundPath(removeBase('/priv-kit/zh/playground/', '/priv-kit/')!), true);
  });
  it('detects the locale from the site path', () => {
    assert.equal(localeFromSitePath('/'), 'en');
    assert.equal(localeFromSitePath('/guide/binder'), 'en');
    assert.equal(localeFromSitePath('/zh/'), 'zh');
    assert.equal(localeFromSitePath('/zh/guide/binder'), 'zh');
  });

  it('adds and removes the Chinese locale prefix', () => {
    assert.equal(localizeSitePath('/', 'zh'), '/zh/');
    assert.equal(localizeSitePath('/guide/binder', 'zh'), '/zh/guide/binder');
    assert.equal(localizeSitePath('/zh/', 'en'), '/');
    assert.equal(localizeSitePath('/zh/guide/binder', 'en'), '/guide/binder');
  });

  it('does not change paths that already use the target locale', () => {
    assert.equal(localizeSitePath('/guide/binder', 'en'), '/guide/binder');
    assert.equal(
      localizeSitePath('/zh/guide/binder', 'zh'),
      '/zh/guide/binder',
    );
  });
});

describe('deployment base paths', () => {
  it('removes and reapplies a configured base', () => {
    assert.equal(removeBase('/priv-kit/guide/binder', '/priv-kit/'), '/guide/binder');
    assert.equal(applyBase('/zh/guide/binder', '/priv-kit/'), '/priv-kit/zh/guide/binder');
  });

  it('handles the base root and rejects unrelated paths', () => {
    assert.equal(removeBase('/guide/binder', '/'), '/guide/binder');
    assert.equal(removeBase('/other/guide/binder', '/priv-kit/'), null);
  });
});
