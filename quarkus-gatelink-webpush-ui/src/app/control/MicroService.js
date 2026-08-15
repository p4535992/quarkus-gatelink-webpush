const configuredBaseURI = globalThis.GATELINK_BASE_URI || "http://localhost:8080";
const baseURI = configuredBaseURI.replace(/\/+$/, '');
const debug = false;

const resourceURI = resource => `${baseURI}/${resource.replace(/^\/+/, '')}`;

const post = (resource, body) => request(resource, 'POST', body);
const put = (resource, body) => request(resource, 'PUT', body);
const del = resource => bodylessRequest(resource, 'DELETE');
const get = resource => bodylessRequest(resource, 'GET');

const bodylessRequest = async (resource, method) => {
    const requestConfig = {
        method,
        headers: {
            "Accept": "application/json"
        }
    };
    const uri = resourceURI(resource);
    if (debug) {
        console.info(`${method} ${uri}`);
    }
    return fetch(uri, requestConfig);
};

const request = (resource, method, body) => {
    const payload = JSON.stringify(body);
    const requestConfig = {
        method,
        body: payload,
        headers: {
            "Accept": "application/json",
            "Content-Type": "application/json"
        }
    };
    const uri = resourceURI(resource);
    if (debug) {
        console.info(`${method} ${uri}`);
    }
    return fetch(uri, requestConfig);
};

export { post, put, del, get };
