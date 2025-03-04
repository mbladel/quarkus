import {css, html, QwcHotReloadElement} from 'qwc-hot-reload-element';
import {RouterController} from 'router-controller';
import {JsonRpc} from 'jsonrpc';
import '@vaadin/icon';
import '@vaadin/button';
import '@vaadin/combo-box';
import '@vaadin/grid';
import '@vaadin/progress-bar';
import '@vaadin/tabs';
import '@vaadin/tabsheet';
import {columnBodyRenderer} from '@vaadin/grid/lit.js';
import {notifier} from 'notifier';

export class HibernateOrmHqlConsoleComponent extends QwcHotReloadElement {
    jsonRpc = new JsonRpc(this);
    configJsonRpc = new JsonRpc("devui-configuration");

    routerController = new RouterController(this);

    static styles = css`
        .bordered {
            border: 1px solid var(--lumo-contrast-20pct);
            border-radius: var(--lumo-border-radius-l);
            padding: var(--lumo-space-s) var(--lumo-space-m);
        }
        
        .dataSources {
            display: flex;
            flex-direction: column;
            gap: 20px;
            height: 100%;
            padding-left: 10px;
        }

        .tablesAndData {
            display: flex;
            height: 100%;
            gap: 20px;
            padding-right: 20px;
        }
        
        .tables {
            display: flex;
            flex-direction: column;
            gap: 20px;
        }

        .tableData {
            display: flex;
            flex-direction: column;
            width: 100%;
        }

        .tablesCard {
            min-width: 192px;
            display: flex;
        }

        .fill {
            width: 100%;
            height: 100%;
        }

        .small-icon {
            height: var(--lumo-icon-size-s);
            width: var(--lumo-icon-size-s);
        }

        .hqlInput {
            display: flex;
            justify-content: space-between;
            gap: 10px;
            align-items: center;
            padding-bottom: 20px;
            border-bottom-style: dotted;
            border-bottom-color: var(--lumo-contrast-10pct);
        }

        #hql {
            width: 100%;
        }

        .data {
            display: flex;
            flex-direction: column;
            gap: 10px;
            width: 100%;
            height: 100%;
        }

        .pager {
            display: flex;
            justify-content: space-between;
        }

        .hidden {
            visibility: hidden;
        }

        a, a:visited, a:focus, a:active {
            text-decoration: none;
            color: var(--lumo-body-text-color);
        }

        a:hover {
            text-decoration: none;
            color: var(--lumo-primary-text-color);
        }
        
        .font-large {
            font-size: var(--lumo-font-size-l);
        }
        
        .no-margin {
            margin: 0;
        }
    `;


    static properties = {
        _persistenceUnits: {state: true, type: Array},
        _selectedPersistenceUnit: {state: true},
        _selectedEntity: {state: true},
        _selectedEntityIndex: {state: true},
        _currentHQL: {state: true},
        _currentDataSet: {state: true},
        _currentPageNumber: {state: true},
        _currentNumberOfPages: {state: true},
        _allowHql: {state: true},
    }

    constructor() {
        super();
        this._persistenceUnits = [];
        this._selectedPersistenceUnit = null;
        this._selectedEntity = null;
        this._selectedEntityIndex = 0;
        this._currentHQL = null;
        this._currentDataSet = null;
        this._currentPageNumber = 1;
        this._currentNumberOfPages = 1;
        this._pageSize = 12;
    }

    connectedCallback() {
        super.connectedCallback();

        const page = this.routerController.getCurrentPage();
        if (page && page.metadata) {
            this._allowHql = (page.metadata.allowHql === "true");
        } else {
            this._allowHql = false;
        }

        this.hotReload();
    }

    hotReload() {
        this.jsonRpc.getInfo().then(response => {
            this._persistenceUnits = response.result.persistenceUnits;
            this._selectedPersistenceUnit = this._persistenceUnits[0];
            this._selectedEntity = this._selectedPersistenceUnit.managedEntities[0];
        }).catch(error => {
            console.error("Failed to fetch persistence units:", error);
            this._persistenceUnits = [];
            notifier.showErrorMessage("Failed to fetch persistence units: " + error, "bottom-start", 30);
        });
    }

    render() {
        if (this._persistenceUnits) {
            return this._renderAllPUs();
        } else {
            return this._renderFetchingProgress();
        }
    }

    _renderFetchingProgress() {
        return html`
            <div style="color: var(--lumo-secondary-text-color);width: 95%;">
                <div>Fetching persistence units...</div>
                <vaadin-progress-bar indeterminate></vaadin-progress-bar>
            </div>`;
    }

    _renderAllPUs() {
        return this._persistenceUnits.length === 0
            ? html`
                    <p>No persistence units were found.
                        <vaadin-button @click="${this.hotReload}" theme="small">Check again</vaadin-button>
                    </p>`
            : html`
                    <div class="dataSources">
                        ${this._renderTablesAndData()}
                    </div>`;
    }

    _renderDatasourcesComboBox() {
        return html`
            <vaadin-combo-box
                    label="Persistence Unit"
                    item-label-path="name"
                    item-value-path="name"
                    .items="${this._persistenceUnits}"
                    .value="${this._persistenceUnits[0]?.name || ''}"
                    @value-changed="${this._onDataSourceChanged}"
                    .allowCustomValue="${false}"
            ></vaadin-combo-box>
        `;
    }

    _onDataSourceChanged(event) {
        const selectedValue = event.detail.value;
        this._selectedPersistenceUnit = this._persistenceUnits.find(unit => unit.name === selectedValue);
    }

    _renderSelectedDatasource() {
        if (this._selectedPersistenceUnit) {
            return html`<code>${this._selectedPersistenceUnit.name}</code>`; // todo marco : render something useful here
        }
    }

    _renderTablesAndData() {
        return html`
            <div class="tablesAndData">
                <div class="tables">
                    ${this._renderDatasourcesComboBox()}
                    ${this._renderTables()}
                </div>
                <div class="tableData bordered">
                    ${this._renderDataAndInput()}
                </div>
            </div>`;
    }

    _renderTables() {
        if (this._selectedPersistenceUnit) {
            return html`
                <qui-card class="tablesCard" header="Entities">
                    <div slot="content">
                        <vaadin-list-box selected="0" @selected-changed="${this._onEntityChanged}">
                            ${this._selectedPersistenceUnit.managedEntities.map((entity) =>
                                    html`
                                        <vaadin-item>${entity.name}</vaadin-item>`
                            )}
                        </vaadin-list-box>
                    </div>
                </qui-card>`;
        } else {
            return this._renderFetchingProgress();
        }
    }

    _onEntityChanged(event) {
        this._selectedEntityIndex = event.detail.value;
        this._selectedEntity = this._selectedPersistenceUnit.managedEntities[this._selectedEntityIndex];
        this._clearHqlInput();
    }

    _clearHqlInput() {
        if (this._selectedEntity) {
            this._executeHQL("from " + this._selectedEntity.name);
        }
    }

    _executeHQL(hql) {
        this._currentHQL = hql.trim();
        this._executeCurrentHQL();
    }

    _executeClicked() {
        let newValue = this.shadowRoot.getElementById('hql').getAttribute('value');
        this._currentPageNumber = 1;
        this._executeHQL(newValue);
    }

    _executeCurrentHQL() {
        if (this._currentHQL) {
            this.jsonRpc.executeHQL({
                persistenceUnit: this._selectedPersistenceUnit.name,
                hql: this._currentHQL,
                pageNumber: this._currentPageNumber,
                pageSize: this._pageSize
            }).then(jsonRpcResponse => {
                if (jsonRpcResponse.result.error) {
                    notifier.showErrorMessage(jsonRpcResponse.result.error);
                } else {
                    this._currentDataSet = jsonRpcResponse.result;
                    this._currentNumberOfPages = this._getNumberOfPages();
                }
            });
        }
    }

    // *** data table and HQL input ***

    _renderDataAndInput() {
        return html`
            ${this._renderHqlInput()}
            <div tab="data-tab" style="height:100%;">${this._renderTableData()}</div>`;
    }

    _renderHqlInput() {
        if (this._allowHql) {
            return html`
                <div class="hqlInput">
                    <qui-code-block @shiftEnter=${this._shiftEnterPressed} class="font-large" content="${this._currentHQL}" id="hql"
                                    mode="sql" theme="dark" value='${this._currentHQL}' editable></qui-code-block>
                    <vaadin-button class="no-margin" slot="suffix" theme="icon" aria-label="Clear">
                        <vaadin-tooltip .hoverDelay=${500} slot="tooltip" text="Clear"></vaadin-tooltip>
                        <vaadin-icon class="small-icon" @click=${this._clearHqlInput} icon="font-awesome-solid:trash"></vaadin-icon>
                    </vaadin-button>
                    <vaadin-button class="no-margin" slot="suffix" theme="icon" aria-label="Run">
                        <vaadin-tooltip .hoverDelay=${500} slot="tooltip" text="Run"></vaadin-tooltip>
                        <vaadin-icon class="small-icon" @click=${this._executeClicked} icon="font-awesome-solid:play"></vaadin-icon>
                    </vaadin-button>
                </div>`;
        } else {
            return html`
                <vaadin-button theme="small" @click="${this._handleAllowHqlChange}">Allow HQL execution from here
                </vaadin-button>`;
        }
    }

    _handleAllowHqlChange() {
        this.configJsonRpc.updateProperty({
            'name': '%dev.quarkus.hibernate-orm.dev-ui.allow-hql',
            'value': 'true'
        }).then(e => {
            this._allowHql = true;
        });
    }

    _shiftEnterPressed(event) {
        this._executeHQL(event.detail.content);
    }

    _renderTableData() {
        // todo marco : is it ok to use Object.keys() for column names ?
        if (this._selectedEntity && this._currentDataSet) {
            return html`
                <div class="data">
                    <vaadin-grid .items="${this._currentDataSet.data}" theme="row-stripes no-border" style="width:100%;max-height:100%;">
                                 column-reordering-allowed>
                        ${Object.keys(this._currentDataSet.data[0]).map((col) =>
                                this._renderTableHeader(col)
                        )}
                        <span slot="empty-state">No data.</span>
                    </vaadin-grid>
                    ${this._renderPager()}
                </div>
            `;
        } else {
            return html`
                <div style="color: var(--lumo-secondary-text-color);width: 95%;">
                    <div>Fetching data...</div>
                    <vaadin-progress-bar indeterminate></vaadin-progress-bar>
                </div>`;
        }
    }

    _renderTableHeader(col) {
        return html`
            <vaadin-grid-sort-column path="${col}" header="${col}" auto-width resizable ${columnBodyRenderer(
                    (item) => this._cellRenderer(col, item),
                    []
            )}></vaadin-grid-sort-column>`;
    }

    _cellRenderer(columnName, item) {
        const value = item[columnName];
        if (value) {
            // todo marco : should we have some formatting options here ?
            return html`<span>${value}</span>`;
        }
    }

    // *** pager and page handling ***

    _renderPager() {
        return html`
            <div class="pager">
                ${this._renderPreviousPageButton()}
                <span>${this._currentPageNumber} of ${this._currentNumberOfPages}</span>
                ${this._renderNextPageButton()}
            </div>`;
    }

    _renderPreviousPageButton() {
        let klas = "pageButton";
        if (this._currentPageNumber === 1) {
            klas = "hidden";
        }
        return html`
            <vaadin-button theme="icon tertiary" aria-label="Previous" @click=${this._previousPage} class="${klas}">
                <vaadin-icon icon="font-awesome-solid:circle-chevron-left"></vaadin-icon>
            </vaadin-button>`;
    }

    _renderNextPageButton() {
        let klas = "pageButton";
        if (this._currentPageNumber === this._currentNumberOfPages) {
            klas = "hidden";
        }
        return html`
            <vaadin-button theme="icon tertiary" aria-label="Next" @click=${this._nextPage} class="${klas}">
                <vaadin-icon icon="font-awesome-solid:circle-chevron-right"></vaadin-icon>
            </vaadin-button>`;
    }

    _previousPage() {
        if (this._currentPageNumber !== 1) {
            this._currentPageNumber = this._currentPageNumber - 1;
            this._executeCurrentHQL();
        }
    }

    _nextPage() {
        this._currentPageNumber = this._currentPageNumber + 1;
        this._executeCurrentHQL();
    }

    _getNumberOfPages() {
        if (this._currentDataSet) {
            if (this._currentDataSet.totalNumberOfElements > this._pageSize) {
                return Math.ceil(this._currentDataSet.totalNumberOfElements / this._pageSize);
            } else {
                return 1;
            }
        }
    }
}

customElements.define('hibernate-orm-hql-console', HibernateOrmHqlConsoleComponent);