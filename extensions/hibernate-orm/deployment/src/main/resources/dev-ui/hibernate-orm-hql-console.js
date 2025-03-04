import {css, html, QwcHotReloadElement} from 'qwc-hot-reload-element';
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

    static styles = css`
        :host {
            display: flex;
            padding-left: 10px;
            padding-right: 10px;
        }

        .full-height {
            height: 100%;
            width: 100%;
        }

        // todo marco : copied this from agroal extension, remove what's not necessary

        .dataSources {
            display: flex;
            flex-direction: column;
            gap: 20px;
            height: 100%;
            padding-left: 10px;
        }

        .dataSourcesHeader {
            display: flex;
            align-items: baseline;
            gap: 20px;
            border-bottom-style: dotted;
            border-bottom-color: var(--lumo-contrast-10pct);
            padding-bottom: 10px;
            justify-content: space-between;
            padding-right: 20px;
        }

        .dataSourcesHeaderLeft {
            display: flex;
            align-items: baseline;
            gap: 20px;
        }

        .tablesAndData {
            display: flex;
            height: 100%;
            gap: 20px;
        }

        .tableData {
            width: 100%;
            padding-right: 20px;
        }

        .tablesCard {
            min-width: 192px;
            display: flex;
        }

        .fill {
            width: 100%;
            height: 100%;
        }

        .pkicon {
            height: var(--lumo-icon-size-s);
            width: var(--lumo-icon-size-s);
        }

        .sqlInput {
            display: flex;
            justify-content: space-between;
            gap: 10px;
        }

        #sql {
            width: 100%;
        }

        .data {
            display: flex;
            flex-direction: column;
            gap: 10px;
            width: 100%;
            height: 100%;
        }

        .sqlInputButton {
            height: var(--lumo-icon-size-s);
            width: var(--lumo-icon-size-s);
            cursor: pointer;
            color: var(--lumo-contrast-50pct);
        }
        
        .sqlInputButton:hover {
            color: var(--lumo-contrast-80pct);
        }

        .pager {
            display: flex;
            justify-content: space-between;
        }

        .hidden {
            visibility: hidden;
        }

        .download {
            cursor: pointer;
            text-decoration: none;
            color: var(--lumo-body-text-color);
        }

        .download:hover {
            color: var(--lumo-primary-text-color);
            text-decoration: underline;
        }

        a, a:visited, a:focus, a:active {
            text-decoration: none;
            color: var(--lumo-body-text-color);
        }

        a:hover {
            text-decoration: none;
            color: var(--lumo-primary-text-color);
        }
    `;


    static properties = {
        _persistenceUnits: {state: true, type: Array},
        _selectedPersistenceUnits: {state: true},
        _selectedDataSource: {state: true},
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
        this._selectedDataSource = null;
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
        if(page && page.metadata){
            this._allowHql = (page.metadata.allowHql === "true");
        }else{
            this._allowHql = false;
        }

        this.hotReload();
    }

    hotReload() {
        this.jsonRpc.getInfo().then(response => {
            this._persistenceUnits = response.result.persistenceUnits;
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
        return this._persistenceUnits.length == 0
            ? html`
                    <p>No persistence units were found.
                        <vaadin-button @click="${this.hotReload}" theme="small">Check again</vaadin-button>
                    </p>`
            : html`
                    <div class="dataSources">
                        <div class="dataSourcesHeader">
                            <div class="dataSourcesHeaderLeft">
                                ${this._renderDatasourcesComboBox()}
                                ${this._renderSelectedDatasource()}
                            </div>
                        </div>
                        ${this._renderTablesAndData()}
                    </div>`;
    }

    _renderDatasourcesComboBox() {
        return html`
            <vaadin-combo-box
                    label="Persistence Unit"
                    item-label-path="name"
                    item-value-path="name"
                    .items="${Object.values(this._persistenceUnits)}"
                    .value="${Object.values(this._persistenceUnits)[0]?.name || ''}"
                    @value-changed="${this._onDataSourceChanged}"
                    .allowCustomValue="${false}"
            ></vaadin-combo-box>
        `;
    }

    _renderSelectedDatasource() {
        if (this._selectedDataSource) {
            return html`<code>${this._selectedDataSource.name}</code>`; // todo marco : render something useful here
        }
    }

    _renderTablesAndData() {
        return html`
            <div class="tablesAndData">
                <div class="tables">
                    ${this._renderTables()}
                </div>
                <div class="tableData">
                    ${this._renderDataAndInput()}
                </div>
            </div>`;
    }

    _renderTables() {
        if (this._persistenceUnits) {
            return html`
                <qui-card class="tablesCard" header="Tables">
                    <div slot="content">
                        <vaadin-list-box selected="0" @selected-changed="${this._onTableChanged}">
                            ${this._persistenceUnits.managedEntities.map((entity) =>
                                    html`
                                        <vaadin-item>${entity.className}</vaadin-item>`
                            )}
                        </vaadin-list-box>
                    </div>
                </qui-card>`;
        } else {
            return this._renderFetchingProgress();
        }
    }

    _onTableChanged(event) {
        this._selectedEntityIndex = event.detail.value;
        this._selectedEntity = this._persistenceUnits.managedEntities[this._selectedEntityIndex];
        this._clearHqlInput();
    }

    _clearHqlInput() {
        if (this._selectedTable) {
            this._executeHQL("from " + this._selectedEntity.className);
        }
    }

    _executeHQL(sql) {
        this._currentHQL = sql.trim();
        this._executeCurrentSQL();
    }

    _executeClicked() {
        let newValue = this.shadowRoot.getElementById('sql').getAttribute('value');
        this._executeHQL(newValue);
    }

    _executeCurrentSQL() {
        if (this._currentSQL) {
            this.jsonRpc.executeHQL({
                pu: this._selectedDataSource.name,
                hql: this._currentHQL,
                pageNumber: this._currentPageNumber,
                pageSize: this._pageSize
            }).then(jsonRpcResponse => {
                if (jsonRpcResponse.result.error) {
                    notifier.showErrorMessage(jsonRpcResponse.result.error);
                } else if (jsonRpcResponse.result.message) {
                    notifier.showInfoMessage(jsonRpcResponse.result.message);
                    this._clearHqlInput();
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
            <vaadin-tabsheet class="fill" theme="bordered">
                <vaadin-button slot="suffix" theme="icon" title="Refresh" aria-label="Refresh">
                    <vaadin-icon @click=${this.hotReload} icon="font-awesome-solid:arrows-rotate"></vaadin-icon>
                </vaadin-button>

                ${this._renderSqlInput()}

                <div tab="data-tab" style="height:100%;">${this._renderTableData()}</div>
            </vaadin-tabsheet>`;
    }

    _renderSqlInput(){
        if(this._allowHql){
            return html`<div class="sqlInput">
                        <qui-code-block @shiftEnter=${this._shiftEnterPressed} content="${this._currentHQL}" id="sql" mode="sql" theme="dark" value='${this._currentHQL}' editable></qui-code-block>
                        <vaadin-icon class="sqlInputButton" title="Clear" icon="font-awesome-solid:trash" @click=${this._clearHqlInput}></vaadin-icon>
                        <vaadin-icon class="sqlInputButton" title="Run (Shift + Enter)" icon="font-awesome-solid:play" @click=${this._executeClicked}></vaadin-icon>
                    </div>`;
        }else {
            return html`<vaadin-button theme="small" @click="${this._handleallowHqlChange}">Allow HQL execution from here</vaadin-button>`;
        }
    }

    _handleallowHqlChange(){
        this.configJsonRpc.updateProperty({
            'name': '%dev.quarkus.datasource.dev-ui.allow-hql',
            'value': 'true'
        }).then(e => {
            this._allowHql = true;
        });
    }

    _shiftEnterPressed(event){
        this._executeSQL(event.detail.content);
    }

    _renderTableData() {
        if (this._selectedEntity && this._currentDataSet && this._currentDataSet.cols) {
            return html`
                <div class="data">
                    <vaadin-grid .items="${this._currentDataSet.data}" theme="row-stripes no-border" class="fill"
                                 column-reordering-allowed>
                        ${this._currentDataSet.cols.map((col) =>
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
            <vaadin-grid-sort-column path="${col}" header="${heading}" auto-width resizable ${columnBodyRenderer(
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

    _renderPreviousPageButton(){
        let klas = "pageButton";
        if(this._currentPageNumber === 1){
            klas = "hidden";
        }
        return html`<vaadin-button theme="icon tertiary" aria-label="Previous" @click=${this._previousPage} class="${klas}">
                        <vaadin-icon icon="font-awesome-solid:circle-chevron-left"></vaadin-icon>
                    </vaadin-button>`;
    }

    _renderNextPageButton(){
        let klas = "pageButton";
        if(this._currentPageNumber === this._currentNumberOfPages){
            klas = "hidden";
        }
        return html`<vaadin-button theme="icon tertiary" aria-label="Next" @click=${this._nextPage} class="${klas}">
                        <vaadin-icon icon="font-awesome-solid:circle-chevron-right"></vaadin-icon>
                    </vaadin-button>`;
    }

    _previousPage(){
        if(this._currentPageNumber!==1){
            this._currentPageNumber = this._currentPageNumber - 1;
            this._executeCurrentSQL();
        }
    }

    _nextPage(){
        this._currentPageNumber = this._currentPageNumber + 1;
        this._executeCurrentSQL();
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