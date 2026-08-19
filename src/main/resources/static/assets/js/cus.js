// Минимальный сценарий интерфейса (UI-0.2): собственный JS без сборщика и без внешних библиотек.
(function () {
    'use strict';

    // Токен CSRF уезжает со всеми запросами HTMX: цепочка интерфейса защищена по UI-0.3.
    var tokenMeta = document.querySelector('meta[name="_csrf"]');
    var headerMeta = document.querySelector('meta[name="_csrf_header"]');
    if (tokenMeta && headerMeta && headerMeta.content) {
        document.body.addEventListener('htmx:configRequest', function (event) {
            event.detail.headers[headerMeta.content] = tokenMeta.content;
        });
    }

    // Диалоги подтверждения приходят фрагментом HTMX; показываем модальное окно, когда контент загружен.
    document.body.addEventListener('htmx:afterSwap', function (event) {
        if (event.detail.target && event.detail.target.id === 'cus-modal-content') {
            var modalElement = document.getElementById('cus-modal');
            var modal = bootstrap.Modal.getOrCreateInstance(modalElement);
            modal.show();
        }
        if (event.detail.target && event.detail.target.id === 'card-body') {
            var openModal = bootstrap.Modal.getInstance(document.getElementById('cus-modal'));
            if (openModal) {
                openModal.hide();
            }
        }
    });

    // UI-0.6: подтверждение необратимых действий. Текст приходит в data-confirm — подставлять строки
    // в onsubmit Thymeleaf не даёт, и это правильно: так значение не может стать кодом.
    document.querySelectorAll('form[data-confirm]').forEach(function (form) {
        form.addEventListener('submit', function (event) {
            if (!window.confirm(form.getAttribute('data-confirm'))) {
                event.preventDefault();
            }
        });
    });

    // Вставка плейсхолдера в тело формы (UI-8) и копирование контрольной суммы (UI-10).
    document.querySelectorAll('[data-insert-into]').forEach(function (button) {
        button.addEventListener('click', function () {
            var target = document.getElementById(button.getAttribute('data-insert-into'));
            if (target) {
                target.value += button.getAttribute('data-insert');
            }
        });
    });
    document.querySelectorAll('[data-copy]').forEach(function (button) {
        button.addEventListener('click', function () {
            navigator.clipboard.writeText(button.getAttribute('data-copy'));
        });
    });

    // UI-0.9: предупреждение о несохранённых изменениях на страницах редактирования.
    document.querySelectorAll('form[data-warn-unsaved]').forEach(function (form) {
        var dirty = false;
        form.addEventListener('change', function () {
            dirty = true;
        });
        form.addEventListener('submit', function () {
            dirty = false;
        });
        window.addEventListener('beforeunload', function (event) {
            if (dirty) {
                event.preventDefault();
                event.returnValue = '';
            }
        });
    });
})();
