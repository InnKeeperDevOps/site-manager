/**
 * Tests for the registerView HTML section and the register link in loginView.
 */

const fs = require('fs');
const path = require('path');

const htmlPath = path.resolve(__dirname, '../../main/resources/static/index.html');
const html = fs.readFileSync(htmlPath, 'utf8');

describe('registerView section', () => {
    let document;

    beforeEach(() => {
        document = new DOMParser().parseFromString(html, 'text/html');
    });

    test('registerView div exists', () => {
        const view = document.getElementById('registerView');
        expect(view).not.toBeNull();
    });

    test('registerView is hidden by default', () => {
        const view = document.getElementById('registerView');
        expect(view.style.display).toBe('none');
    });

    test('registerView contains an h2 with text "Create Account"', () => {
        const view = document.getElementById('registerView');
        const heading = view.querySelector('h2');
        expect(heading).not.toBeNull();
        expect(heading.textContent.trim()).toBe('Create Account');
    });

    test('registerForm exists with correct onsubmit handler', () => {
        const form = document.getElementById('registerForm');
        expect(form).not.toBeNull();
        expect(form.getAttribute('onsubmit')).toBe('app.register(event)');
    });

    test('registerUsername input exists', () => {
        expect(document.getElementById('registerUsername')).not.toBeNull();
    });

    test('registerPassword input exists', () => {
        expect(document.getElementById('registerPassword')).not.toBeNull();
    });

    test('registerConfirmPassword input exists', () => {
        expect(document.getElementById('registerConfirmPassword')).not.toBeNull();
    });

    test('registerForm has a submit button labeled "Create Account"', () => {
        const form = document.getElementById('registerForm');
        const btn = form.querySelector('button[type="submit"]');
        expect(btn).not.toBeNull();
        expect(btn.textContent.trim()).toBe('Create Account');
    });

    test('registerView has a "Back to Login" link that navigates to login', () => {
        const view = document.getElementById('registerView');
        const links = Array.from(view.querySelectorAll('a'));
        const backLink = links.find(a => a.textContent.trim() === 'Back to Login');
        expect(backLink).not.toBeUndefined();
        expect(backLink.getAttribute('onclick')).toContain("app.navigate('login')");
    });
});

describe('loginView register link', () => {
    let document;

    beforeEach(() => {
        document = new DOMParser().parseFromString(html, 'text/html');
    });

    test('loginView has a "Create an account" link', () => {
        const loginView = document.getElementById('loginView');
        const links = Array.from(loginView.querySelectorAll('a'));
        const registerLink = links.find(a => a.textContent.trim() === 'Create an account');
        expect(registerLink).not.toBeUndefined();
    });

    test('"Create an account" link navigates to register', () => {
        const loginView = document.getElementById('loginView');
        const links = Array.from(loginView.querySelectorAll('a'));
        const registerLink = links.find(a => a.textContent.trim() === 'Create an account');
        expect(registerLink.getAttribute('onclick')).toContain("app.navigate('register')");
    });
});
