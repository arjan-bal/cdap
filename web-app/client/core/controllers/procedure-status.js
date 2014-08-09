/*
 * Procedure Controller
 */

define([], function () {

	var Controller = Ember.Controller.extend({

		needs: ['Procedure'],

		load: function () {

			this.clearTriggers(true);

			var model = this.get('model');
			var self = this;

			var controller = this.get('controllers.Procedure');

			if (controller.get('previousProcedure') !== model.get('id')) {
				controller.set('requestMethod', '');
				controller.set('requestParams', '');
				controller.set('responseBody', '');
				controller.set('previousProcedure', model.get('id'));
			}

			/*
			 * Track container metric.
			 */
			model.trackMetric('/reactor/' + model.get('context') + '/resources.used.containers', 'currents', 'containers');

			this.interval = setInterval(function () {
				self.updateStats();
			}, C.POLLING_INTERVAL);

			/*
			 * Give the chart Embeddables 100ms to configure
			 * themselves before updating.
			 */
			setTimeout(function () {
				self.updateStats();
			}, C.EMBEDDABLE_DELAY);

		},

		unload: function () {

			clearInterval(this.interval);

		},

		ajaxCompleted: function () {
			return this.get('timeseriesCompleted') && this.get('aggregatesCompleted');
		},

		clearTriggers: function (value) {
			this.set('timeseriesCompleted', value);
			this.set('aggregatesCompleted', value);
		},

		updateStats: function () {
			if (!this.ajaxCompleted()) {
				return;
			}
			this.get('model').updateState(this.HTTP);
			this.clearTriggers(false);
			C.Util.updateTimeSeries([this.get('model')], this.HTTP, this);
			C.Util.updateAggregates([this.get('model')], this.HTTP, this);

			C.Util.updateCurrents([this.get('model')], this.HTTP, this, C.RESOURCE_METRICS_BUFFER);

			var appId = this.get('model.app');
			var procedureName = this.get('model.name');
			var self = this;

			this.HTTP.get('rest', 'apps', appId, 'procedures', procedureName, 'instances', function (response) {

				self.set('model.instances', response.instances);

			});

		},

		/**
		 * Action handlers from the View
		 */
		exec: function () {

			var model = this.get('model');
			var action = model.get('defaultAction');
			if (action && action.toLowerCase() in model) {
				model[action.toLowerCase()](this.HTTP);
			}

		},

    changeInstances: function () {
      var inputStr = this.get('instancesInput');
      var input = parseInt(inputStr);
      this.set('instancesInput', '');

      if(!inputStr || inputStr.length === 0){
        C.Modal.show('Change Instances','Enter a valid number of instances.');
        return;
      }

      if(isNaN(input) || isNaN(inputStr)){
        C.Modal.show('Incorrect Input', 'Instance count can only be set to numbers (between 1 and 100).');
        return;
      }

      if(input < 1 || input > 100) {
        C.Modal.show('Instances Requested out of bounds', 'Please select an instance count (between 1 and 100)');
        return;
      }

      this.setInstances(input, "Change Instances to " + input + " for ");
    },

		setInstances: function (instances, message) {

			var self = this;
			var appId = this.get('model.app');
			var procedureName = this.get('model.name');

			C.Modal.show(
				"Procedure Instances",
				message + ' "' + procedureName + '" procedure?',
				function () {
//
					self.HTTP.put('rest', 'apps', appId, 'procedures', procedureName, 'instances', {
						data: '{"instances": ' + instances + '}'
					}, function (response) {

						self.set('model.instances', instances);

					});
				});

		},

		addOneInstance: function () {

			var instances = this.get('model.instances');
			instances ++;

			if (instances >= 1 && instances <= 64) {
				this.setInstances(instances, 'Add an instance to');
			}

		},

		removeOneInstance: function () {

			var instances = this.get('model.instances');
			instances --;

			if (instances >= 1 && instances <= 64) {
				this.setInstances(instances, 'Remove an instance from');
			}

		},

    actualInstances: function () {
      if (C.get('isLocal')) {
        return this.get('model.instances');
      } else {
        var instances = Math.max(0, (+this.get('model.containersLabel') - 1));
        return instances + ' instance' + (instances === 1 ? '' : 's');
      }
    }.property('model.instances','model.containersLabel'),

		config: function () {

			var self = this;
			var model = this.get('model');

			this.transitionToRoute('ProcedureStatus.Config');

		},

		responseBody: null,
		responseCode: null,

		submit: function (event) {

			var self = this;
			var controller = this.get('controllers.Procedure');

			var appId = this.get('model').applicationId;
			var procedureName = this.get('model').serviceName;
			var methodName = controller.get('requestMethod');

			this.HTTP.post('rest', 'apps', appId, 'procedures', procedureName, 'methods', methodName, {
					data: controller.get('requestParams')
				}, function (response) {

				if (response) {
					if (typeof(response) === 'object') {
						self.set('responseBody', JSON.stringify(response, undefined, 2) || '[ No content ]');
					} else {
						self.set('responseBody', response || '[ No content ]');
					}

				} else {
					self.set('responseBody', '[ No response received ]');
				}

			});

		}

	});

	Controller.reopenClass({
		type: 'ProcedureStatus',
		kind: 'Controller'
	});

	return Controller;

});