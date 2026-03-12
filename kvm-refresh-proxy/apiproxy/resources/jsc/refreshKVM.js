/**
 * KVM Cache Refresh Script for Apigee Edge
 *
 * This script refreshes KVM cache by:
 * 1. Getting the current value via Management API
 * 2. Re-PUTting the same value (which invalidates the MP cache)
 *
 * Required context variables:
 * - kvm.mapName: Name of the KVM
 * - kvm.keyName: Key to refresh (optional, refreshes all if not specified)
 * - kvm.scope: 'environment' or 'organization' (default: environment)
 * - private.mgmt.credentials: Base64 encoded credentials for Management API
 */

var kvmName = context.getVariable('extracted.kvm.mapName') || context.getVariable('request.queryparam.kvmName');
var keyName = context.getVariable('extracted.kvm.keyName') || context.getVariable('request.queryparam.keyName');
var scope = context.getVariable('extracted.kvm.scope') || 'environment';

var org = context.getVariable('organization.name');
var env = context.getVariable('environment.name');

// Store for response
context.setVariable('kvm.refreshed.mapName', kvmName);
context.setVariable('kvm.refreshed.keyName', keyName || 'all');
context.setVariable('kvm.refreshed.scope', scope);
context.setVariable('kvm.refreshed.org', org);
context.setVariable('kvm.refreshed.env', env);

// Build Management API URL
var baseUrl = 'https://api.enterprise.apigee.com/v1/organizations/' + org;

if (scope === 'organization') {
    context.setVariable('kvm.mgmtApiUrl', baseUrl + '/keyvaluemaps/' + kvmName);
} else {
    context.setVariable('kvm.mgmtApiUrl', baseUrl + '/environments/' + env + '/keyvaluemaps/' + kvmName);
}

if (keyName) {
    context.setVariable('kvm.mgmtApiUrl', context.getVariable('kvm.mgmtApiUrl') + '/entries/' + keyName);
}

// Log for debugging
print('KVM Refresh Request:');
print('  Map: ' + kvmName);
print('  Key: ' + (keyName || 'ALL'));
print('  Scope: ' + scope);
print('  URL: ' + context.getVariable('kvm.mgmtApiUrl'));
