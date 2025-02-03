document.addEventListener('DOMContentLoaded', function () {
    const multiSelects = document.querySelectorAll('.multi-select');

    multiSelects.forEach(function (multiSelect) {
        const checkboxes = multiSelect.querySelectorAll('input[type="checkbox"]');

        checkboxes.forEach(function (checkbox) {
            checkbox.addEventListener('change', function () {
                updateMultiSelectValue(multiSelect);
            });
        });
    });
});

function updateMultiSelectValue(multiSelect) {
    const checkboxes = multiSelect.querySelectorAll('input[type="checkbox"]:checked');
    const values = Array.from(checkboxes).map(cb => cb.value);
    console.log('Selected values:', values);
    // You can send these values to the server or process them as needed
}

function initForm() {
    const form = document.querySelector('#form');
    form.innerHTML += `
        <div id="inputs"></div>
        <br />

        <button type="submit">Save</button>
        <button type="button" onclick="location.reload()">Discard Changes</button>
        `;
    return form.querySelector('#inputs');
}

function appendCheckbox(container, label, id, checked) {
    const labelElement = document.createElement('label');
    labelElement.innerText = label;
    const input = document.createElement('input');
    container.appendChild(labelElement);
    labelElement.appendChild(input);
    input.setAttribute("type", "checkbox");
    input.setAttribute("name", id);
    if (checked) {
        input.setAttribute("checked", "checked");
    }
    return input;
}

function appendNumber(container, label, id, value) {
    const labelElement = document.createElement('label');
    labelElement.innerText = label;
    const input = document.createElement('input');
    container.appendChild(labelElement);
    labelElement.appendChild(input);
    input.setAttribute("type", "number");
    input.setAttribute("name", id);
    input.setAttribute("value", value);
    return input;
}

function appendText(container, label, id, value) {
    const labelElement = document.createElement('label');
    labelElement.innerText = label;
    const input = document.createElement('input');
    container.appendChild(labelElement);
    labelElement.appendChild(input);
    input.setAttribute("type", "text");
    input.setAttribute("name", id);
    input.setAttribute("value", value);
    return input;
}

function appendPassword(container, label, id, value) {
    const labelElement = document.createElement('label');
    labelElement.innerText = label;
    const input = document.createElement('input');
    container.appendChild(labelElement);
    labelElement.appendChild(input);
    input.setAttribute("type", "password");
    input.setAttribute("name", id);
    input.setAttribute("value", value);
    return input;
}

function makeMultiContainer(container, label) {
    const multi = document.createElement('ul');
    const header = document.createElement('h3');
    header.innerHTML = label;
    container.appendChild(header);
    container.appendChild(multi);
    return multi;
}

function appendButton(container, label, fn) {
    const button = document.createElement('button');
    button.innerHTML = label;
    button.setAttribute("type", "button");
    button.onclick = fn;
    container.appendChild(button);
    return button;
}

function makeMultiContainerEntry(multi) {
    const entry = document.createElement('li');
    entry.style = "position: relative;";
    const button = appendButton(entry, "Remove", () => entry.remove());
    button.style = "position: absolute; right: 0; top: 0; margin: 0; padding: 2px 6px;";
    multi.appendChild(entry);
    return entry;
}

function makeSection(container, label) {
    const section = document.createElement('div');
    section.classList.add('section');
    const header = document.createElement('h2');
    header.innerText = label;
    header.style.marginBottom = "0";
    container.appendChild(header);
    container.appendChild(section);
    return section;
}
