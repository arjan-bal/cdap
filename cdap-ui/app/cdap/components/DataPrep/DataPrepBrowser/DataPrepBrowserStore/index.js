/*
 * Copyright © 2017-2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import {createStore, combineReducers} from 'redux';
import {defaultAction, composeEnhancers, objectQuery} from 'services/helpers';

const Actions = {
  // Database
  SET_DATABASE_PROPERTIES: 'SET_DATABASE_PROPERTIES',
  SET_DATABASE_CONNECTION_ID: 'SET_DATABASE_CONNECTION_ID',
  SET_DATABASE_LOADING: 'SET_DATABASE_LOADING',
  SET_ACTIVEBROWSER: 'SET_ACTIVE_BROWSER',

  // Kafka
  SET_KAFKA_PROPERTIES: 'SET_KAFKA_PROPERTIES',
  SET_KAFKA_CONNECTION_ID: 'SET_KAFKA_CONNECTION_ID',
  SET_KAFKA_LOADING: 'SET_KAFKA_LOADING',

  // S3
  SET_S3_LOADING: 'SET_S3_LOADING',
  SET_S3_ACTIVE_BUCKET_DETAILS: 'SET_S3_ACTIVE_BUCKET_DETAILS',
  SET_S3_CONNECTION_DETAILS: 'SET_S3_CONNECTION_DETAILS',
  SET_S3_CONNECTION_ID: 'SET_S3_CONNECTION_ID',
  SET_S3_PREFIX: 'SET_S3_PREFIX',
  SET_S3_DATAVIEW: 'SET_S3_DATAVIEW',
  SET_S3_SEARCH: 'SET_S3_SEARCH',

  // GCS
  SET_GCS_LOADING: 'SET_GCS_LOADING',
  SET_GCS_ACTIVE_BUCKET_DETAILS: 'SET_GCS_ACTIVE_BUCKET_DETAILS',
  SET_GCS_CONNECTION_DETAILS: 'SET_GCS_CONNECTION_DETAILS',
  SET_GCS_CONNECTION_ID: 'SET_GCS_CONNECTION_ID',
  SET_GCS_PREFIX: 'SET_GCS_PREFIX',
  SET_GCS_DATAVIEW: 'SET_GCS_DATAVIEW',
  SET_GCS_SEARCH: 'SET_GCS_SEARCH',

  // Big Query
  SET_BIGQUERY_CONNECTION_ID: 'SET_BIGQUERY_CONNECTION_ID',
  SET_BIGQUERY_CONNECTION_DETAILS: 'SET_BIGQUERY_CONNECTION_DETAILS',
  SET_BIGQUERY_LOADING: 'SET_BIGQUERY_LOADING',
  SET_BIGQUERY_DATASET_LIST: 'SET_BIGQUERY_DATASET_LIST',
  SET_BIGQUERY_TABLE_LIST: 'SET_BIGQUERY_TABLE_LIST',

  SET_ERROR: 'SET_ERROR',
  RESET: 'RESET'
};

export {Actions};

const defaultDatabaseValue = {
  info: {},
  tables: [],
  loading: false,
  connectionId: ''
};

const defaultKafkaValue = {
  info: {},
  topics: [],
  loading: false,
  connectionId: ''
};

const defaultS3Value = {
  info: {},
  loading: false,
  activeBucketDetails: [],
  prefix: '',
  connectionId: '',
  search: ''
};

const defaultGCSValue = {
  info: {},
  loading: false,
  activeBucketDetails: [],
  prefix: '',
  connectionId: '',
  search: ''
};

const defaultBigQueryValue = {
  info: {},
  loading: false,
  connectionId: '',
  datasetId: null,
  datasetList: [],
  tableList: []
};

const defaultActiveBrowser = {
  name: 'database'
};

const defaultError = null;

const database = (state = defaultDatabaseValue, action = defaultAction) => {
  switch (action.type) {
    case Actions.SET_DATABASE_CONNECTION_ID:
      // This means the user is starting afresh. Reset everything to default and set the connectionID
      return {
        ...defaultDatabaseValue,
        connectionId: action.payload.connectionId
      };
    case Actions.SET_DATABASE_PROPERTIES:
      return Object.assign({}, state, {
        info: objectQuery(action, 'payload', 'info') || state.info,
        connectionId: objectQuery(action, 'payload', 'connectionId'),
        tables: objectQuery(action, 'payload', 'tables'),
        error: null,
        loading: false
      });
    case Actions.SET_DATABASE_LOADING:
      return Object.assign({}, state, {
        loading: action.payload.loading,
        error: null
      });
    case Actions.SET_ERROR:
      return {
        ...state,
        loading: false
      };
    case Actions.RESET:
      return defaultDatabaseValue;
    default:
      return state;
  }
};

const kafka = (state = defaultKafkaValue, action = defaultAction) => {
  switch (action.type) {
    case Actions.SET_KAFKA_CONNECTION_ID:
      // This means the user is starting afresh. Reset everything to default and set the connectionID
      return {
        ...defaultKafkaValue,
        connectionId: action.payload.connectionId
      };
    case Actions.SET_KAFKA_PROPERTIES:
      return Object.assign({}, state, {
        info: objectQuery(action, 'payload', 'info') || state.info,
        connectionId: objectQuery(action, 'payload', 'connectionId'),
        topics: objectQuery(action, 'payload', 'topics'),
        error: null,
        loading: false
      });
    case Actions.SET_KAFKA_LOADING:
      return Object.assign({}, state, {
        loading: action.payload.loading,
        error: null
      });
    case Actions.SET_ERROR:
      return {
        ...state,
        loading: false
      };
    case Actions.RESET:
      return defaultKafkaValue;
    default:
      return state;
  }
};

const s3 = (state = defaultS3Value, action = defaultAction) => {
  switch (action.type) {
    case Actions.SET_S3_CONNECTION_ID:
      // This means the user is starting afresh. Reset everything to default and set the connectionID
      return {
        ...defaultS3Value,
        connectionId: action.payload.connectionId
      };
    case Actions.SET_S3_CONNECTION_DETAILS:
      return {
        ...state,
        info: action.payload.info,
        error: null
      };
    case Actions.SET_S3_LOADING:
      return {
        ...state,
        loading: true
      };
    case Actions.SET_S3_ACTIVE_BUCKET_DETAILS:
      return {
        ...state,
        activeBucketDetails: action.payload.activeBucketDetails,
        loading: false
      };
    case Actions.SET_S3_PREFIX:
      return {
        ...state,
        prefix: action.payload.prefix
      };
    case Actions.SET_S3_SEARCH:
      return {
        ...state,
        search: action.payload.search
      };
    case Actions.SET_ERROR:
      return {
        ...state,
        loading: false
      };
    case Actions.RESET:
      return defaultS3Value;
    default:
      return state;
  }
};

const gcs = (state = defaultGCSValue, action = defaultAction) => {
  switch (action.type) {
    case Actions.SET_GCS_CONNECTION_ID:
      // This means the user is starting afresh. Reset everything to default and set the connectionID
      return {
        ...defaultGCSValue,
        connectionId: action.payload.connectionId
      };
    case Actions.SET_GCS_CONNECTION_DETAILS:
      return {
        ...state,
        info: action.payload.info,
        error: null
      };
    case Actions.SET_GCS_LOADING:
      return {
        ...state,
        loading: true
      };
    case Actions.SET_GCS_ACTIVE_BUCKET_DETAILS:
      return {
        ...state,
        activeBucketDetails: action.payload.activeBucketDetails,
        loading: false
      };
    case Actions.SET_GCS_PREFIX:
      return {
        ...state,
        prefix: action.payload.prefix
      };
    case Actions.SET_GCS_SEARCH:
      return {
        ...state,
        search: action.payload.search
      };
    case Actions.SET_ERROR:
      return {
        ...state,
        loading: false
      };
    case Actions.RESET:
      return defaultGCSValue;
    default:
      return state;
  }
};

const bigquery = (state = defaultBigQueryValue, action = defaultAction) => {
  switch (action.type) {
    // This means the user is starting afresh. Reset everything to default and set the connectionID
    case Actions.SET_BIGQUERY_CONNECTION_ID:
      return {
        ...defaultBigQueryValue,
        connectionId: action.payload.connectionId
      };
    case Actions.SET_BIGQUERY_CONNECTION_DETAILS:
      return {
        ...state,
        info: action.payload.info,
        error: null
      };
    case Actions.SET_BIGQUERY_LOADING:
      return {
        ...state,
        loading: true
      };
    case Actions.SET_BIGQUERY_DATASET_LIST:
      return {
        ...state,
        loading: false,
        datasetList: action.payload.datasetList,
        datasetId: null
      };
    case Actions.SET_BIGQUERY_TABLE_LIST:
      return {
        ...state,
        loading: false,
        datasetList: [],
        datasetId: action.payload.datasetId,
        tableList: action.payload.tableList
      };
    case Actions.SET_ERROR:
      return {
        ...state,
        loading: false
      };
    case Actions.RESET:
      return defaultBigQueryValue;
    default:
      return state;
  }
};

const activeBrowser = (state = defaultActiveBrowser, action = defaultAction) => {
  switch (action.type) {
    case Actions.SET_ACTIVEBROWSER:
      return Object.assign({}, state, {
        name: action.payload.name
      });
    default:
      return state;
  }
};

const error = (state = defaultError, action = defaultAction) => {
  switch (action.type) {
    case Actions.SET_ERROR:
      return action.payload.error;
    default:
      return state;
  }
};

const DataPrepBrowserStore = createStore(
  combineReducers({
    database,
    kafka,
    activeBrowser,
    s3,
    gcs,
    bigquery,
    error
  }),
  {
    database: defaultDatabaseValue,
    kafka: defaultKafkaValue,
    activeBrowser: defaultActiveBrowser,
    s3: defaultS3Value,
    gcs: defaultGCSValue,
    bigquery: defaultBigQueryValue,
    error: defaultError
  },
  composeEnhancers('DataPrepBrowserStore')()
);

export default DataPrepBrowserStore;
