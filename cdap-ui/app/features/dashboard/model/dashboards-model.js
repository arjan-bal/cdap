/**
 * Dashboards' model
 * The widgets are implemented in columns to make smoother drag and drop experience
 */

angular.module(PKG.name+'.feature.dashboard').factory('MyDashboardsModel',
function (Widget, MyDataSource, mySettings, $q, myHelpers) {

  var dSrc = new MyDataSource(),
      API_PATH = '/configuration/dashboards';


  function Dashboard (p) {
    p = p || {};
    angular.extend(
      this,
      {
        id: p.id,
        title: p.title || 'Dashboard',
        columns: [],
        numColumn: p.numColumn || 3,
        draggable: true
      }
    );

    if(angular.isArray(p.columns)) {
      angular.forEach(p.columns, function (c) {
        this.push(c.map(function (o) {
          return new Widget(o);
        }));
      }, this.columns);
    }
    else {
      // default is a single empty column
      this.columns.push([]);
    }
    this.checkForEmptyDashboard();
  }


  /**
   * rename a widget
   */
  Dashboard.prototype.renameWidget = function (widget, newName) {
    if(newName) {
      widget.title = newName;
      this.persist();
    }
  };


  /**
   * remove a widget from the active dashboard tab
   * @param  {object} w the widget object
   */
  Dashboard.prototype.removeWidget = function (widget) {
    var that = this;
    angular.forEach(that.columns, function (c, i) {
      that.columns[i] = c.filter(function (p) {
        return widget !== p;
      });
    });
    this.checkForEmptyDashboard();
    this.persist();
  };

  Dashboard.prototype.checkForEmptyDashboard = function() {
    var isEmpty = true;
    angular.forEach(this.columns, function(column) {
      if (column.length > 0) {
        isEmpty = false;
      }
    });
    this.isEmpty = isEmpty;
  };


  /**
   * add a widget to the active dashboard tab
   */
  Dashboard.prototype.addWidget = function (w) {

    function findSmallestColumn(c) {
      var index = 0,
          smallest;
      // find the column with the least widgets
      for (var i = 0; i < c.length; i++) {
        var len = c[i].length;
        if(smallest===undefined || (len < smallest)) {
          smallest = len;
          index = i;
        }
      }
      return index;
    }

    if (angular.isArray(w)) {
      angular.forEach(w, function(widget) {
        this.columns[findSmallestColumn(this.columns)].unshift(widget);
      }.bind(this));
    } else {
      w = w || new Widget({
        title: 'just added'
      });

      this.columns[findSmallestColumn(this.columns)].unshift(w);
    }
    this.persist();
  };



  /**
   * rename dashboard tab
   */
  Dashboard.prototype.rename = function (newName) {
    if(newName) {
      this.title = newName;
      this.persist();
    }
  };



  /**
   * benefit from angular's toJsonReplacer
   */
  Dashboard.prototype.properties = function () {
    return angular.fromJson(angular.toJson(this));
  };


  /**
   * save to backend
   */
  Dashboard.prototype.persist = function () {
    var body = this.properties();

    if(this.id) { // updating
      return dSrc.request(
        {
          method: 'PUT',
          _cdapNsPath: API_PATH + '/' + this.id,
          body: body
        }
      );
    }
    else { // saving
      return dSrc.request(
        {
          method: 'POST',
          _cdapNsPath: API_PATH,
          body: body
        },
        (function (result) {
          this.id = result.id;
        }).bind(this)
      );
    }
  };

  Dashboard.prototype.changeColumn = function(n) {
    this.numColumn = n;

    // Flattening the array
    var array = [];
    array = array.concat.apply(array, this.columns);

    // Create Columns
    var columns = [];
    for (var i = 0; i < this.numColumn; i++) {
      columns.push([]);
    }

    // Fill the columns
    for (var i = 0; i < array.length; i++) {
      columns[i % this.numColumn].push(array[i]);
    }

    this.columns = columns;

    this.persist();
  };

  Dashboard.prototype.toggleDragDrop = function() {
    this.draggable = !this.draggable;

    this.persist();
  };


/* ------------------------------------------------------------------------- */


  function Model (suffix) {
    var data = [],
        self = this,
        deferred = $q.defer();

    data.activeIndex = 0;
    this.data = data;

    this.$promise = deferred.promise;

    this._key = 'dashsbyns';
    if(suffix) { // per-namespace dashboards
      this._key += '.'+suffix;
    }

    mySettings.get(this._key, true)
      .then(function(cfg){
        if(!angular.isArray(cfg)) {
          cfg = [];
        }

        return $q.all(cfg.map(function(id){
          return dSrc.request(
            {
              method: 'GET',

              // at this point, $stateParams.namespace
              // may not be set yet... workaround
              _cdapPath: '/namespaces/' + suffix  + API_PATH + '/' + id
            }
          );
        }));
      })
      .then(function (result) {

        if(result.length) {
          // recreate saved dashboards
          angular.forEach(result, function (v) {
            var p = v.config;
            p.id = v.id;
            data.push(new Dashboard(p));
          });
        }

        deferred.resolve(self);
      }, function(err) {
        console.log("Dashboard delete failed for some reason");
        console.log(err);
        deferred.resolve(self);
      });
  }


  /**
   * direct access to the current tab's Dashboard instance
   */
  Model.prototype.current = function () {
    return this.data[this.data.activeIndex || 0];
  };


  /**
   * save dashboard config to backend
   */
  Model.prototype.persist = function () {
    return mySettings.set(this._key, this.data.map(function(one){
      return one.id;
    }));
  };

  /**
   * remove a dashboard tab
   */
  Model.prototype.remove = function (index) {
    var removed = this.data.splice(index, 1)[0];

    return dSrc.request(
      {
        method: 'DELETE',
        _cdapNsPath: API_PATH + '/' + removed.id
      }
    ).then(function() {
      return this.persist();
    }.bind(this));
  };

  /**
   * reorder dashboards
   * @param  {Boolean} reverse true to move left
   * @return {Number}         new index of current dashboard in reordered array
   */
  Model.prototype.reorder = function (reverse) {
    var max = this.data.length-1,
        index = this.data.activeIndex,
        insert = index + (reverse?-1:1);

    if(insert > max) {
      insert = 0;
    }
    else if(insert < 0) {
      insert = max;
    }

    this.data.splice(insert, 0, this.data.splice(index, 1)[0]);

    this.persist();

    return insert;
  };


  /**
   * add a new dashboard tab
   */
  Model.prototype.add = function (properties, colCount) {
    var d = new Dashboard(properties);

    // add columns as needed
    for (var i = 1; i < (colCount||3); i++) {
      d.columns.push([]);
    }

    this.data.push(d);

    // save to backend
    return d.persist().then((function(){
      return this.persist();
    }).bind(this));
  };



  return Model;
});
